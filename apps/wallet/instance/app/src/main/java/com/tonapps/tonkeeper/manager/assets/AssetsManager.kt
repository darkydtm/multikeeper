package com.tonapps.tonkeeper.manager.assets

import android.content.Context
import com.tonapps.blockchain.model.legacy.TokenEntity
import com.tonapps.blockchain.model.legacy.WalletCurrency
import com.tonapps.blockchain.model.legacy.WalletEntity
import com.tonapps.blockchain.model.legacy.BalanceEntity
import com.tonapps.blockchain.contract.Blockchain
import com.tonapps.blockchain.model.legacy.TokenEntity.Verification
import com.tonapps.blockchain.ton.extensions.equalsAddress
import com.tonapps.deposit.usecase.emulation.EmulationUseCase
import com.tonapps.icu.Coins
import com.tonapps.legacy.enteties.AssetsEntity
import com.tonapps.legacy.enteties.StakedEntity
import com.tonapps.tonkeeper.core.sort
import com.tonapps.tonkeeper.core.sumOfVerifiedFiat
import com.tonapps.wallet.api.API
import com.tonapps.wallet.data.account.AccountRepository
import com.tonapps.wallet.data.rates.RatesRepository
import com.tonapps.wallet.data.settings.SettingsRepository
import com.tonapps.wallet.data.staking.StakingRepository
import com.tonapps.wallet.data.staking.entities.StakingEntity
import com.tonapps.wallet.data.token.TokenRepository
import com.tonapps.wallet.data.token.entities.AccountTokenEntity
import com.tonapps.wallet.data.token.entities.TokenRateEntity
import com.tonapps.wallet.data.gem.Chain as GemChain
import com.tonapps.wallet.data.gem.AssetMetadata as GemAssetMetadata
import com.tonapps.wallet.data.gem.GemWalletDataSource
import com.tonapps.wallet.data.gem.WalletId as GemWalletId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class AssetsManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ratesRepository: RatesRepository,
    private val tokenRepository: TokenRepository,
    private val stakingRepository: StakingRepository,
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val api: API,
    private val gemWalletDataSource: GemWalletDataSource,
) : EmulationUseCase.Delegate {

    data class AssetsResult(
        val assets: List<AssetsEntity>?,
        val refreshedGemChains: Set<GemChain> = emptySet(),
    )

    private val cache = TotalBalanceCache(context)
    private val gemAssetsCache = ConcurrentHashMap<String, Map<GemChain, List<AssetsEntity>>>()
    private val gemAssetsCacheLock = Mutex()

    init {
        settingsRepository.tokenPrefsChangedFlow.drop(1).onEach {
            accountRepository.getSelectedWallet()?.let {
                cache.clear(it, settingsRepository.currency)
                gemAssetsCache.keys.removeIf { key -> key.startsWith("${it.id}:") }
            }
        }.launchIn(scope)
    }

    suspend fun getAssets(
        wallet: WalletEntity,
        currency: WalletCurrency = settingsRepository.currency,
        refresh: Boolean,
        gemChains: Set<GemChain>? = null,
    ): AssetsResult {
        if (wallet.isGem) return getGemAssets(wallet, currency, refresh, gemChains)
        val tokens = getTokens(wallet, currency, refresh)
        var staked = getStaked(wallet, tokens.map { it.token }, currency, refresh)

        val filteredTokens = tokens.filter { !it.token.isLiquid && !it.token.isUSDe }.toMutableList()
        val tokenTsUsde =
            tokens.firstOrNull { it.token.address.equalsAddress(TokenEntity.TON_TS_USDE) }?.token
        val tokenUsde =
            tokens.firstOrNull { it.token.address.equalsAddress(TokenEntity.TON_USDE) }?.token
        tokenUsde?.let {
            if (tokenTsUsde != null) {
                val rates = ratesRepository.getRates(
                    wallet.network,
                    currency,
                    listOf(it.address, tokenTsUsde.address)
                )
                val stakedUsde = rates.convert(
                    from = WalletCurrency.TS_USDE_TON_ETHENA,
                    value = tokenTsUsde.balance.value,
                    to = WalletCurrency.USDE_TON_ETHENA
                )
                val balance =
                    it.balance.copy(value = it.balance.value + stakedUsde)
                filteredTokens.add(
                    AssetsEntity.Token(
                        token = it.copy(
                            balance = balance,
                            fiatRate = TokenRateEntity(
                                currency = currency,
                                fiat = rates.convert(it.address, balance.value),
                                rate = rates.getRate(it.address),
                                rateDiff24h = rates.getDiff24h(it.address)
                            )
                        )
                    )
                )
            } else {
                filteredTokens.add(AssetsEntity.Token(token = it))
            }
        }
        val list = (filteredTokens + staked).sortedBy { it.fiat }.reversed()
        if (list.isEmpty()) {
            return AssetsResult(null)
        }
        return AssetsResult(list)
    }

    suspend fun getToken(
        wallet: WalletEntity, token: String, currency: WalletCurrency = settingsRepository.currency
    ): AssetsEntity.Token? {
        if (wallet.isGem) return getGemAssets(wallet, currency, false).assets.orEmpty().filterIsInstance<AssetsEntity.Token>().firstOrNull { it.address == token }
        val tokens = getTokens(wallet, currency, false)
        return tokens.firstOrNull {
            it.token.address.equalsAddress(token)
        }
    }

    suspend fun getTokens(
        wallet: WalletEntity,
        accountIds: List<String>,
        currency: WalletCurrency = settingsRepository.currency
    ): List<AssetsEntity.Token> = withContext(Dispatchers.IO) {
        if (wallet.isGem) return@withContext getGemAssets(wallet, currency, false).assets.orEmpty().filterIsInstance<AssetsEntity.Token>()
        if (accountIds.isEmpty()) {
            emptyList()
        } else {
            val tokens = getTokens(wallet, currency, false)
            tokens.filter {
                accountIds.any { accountId -> it.token.address.equalsAddress(accountId) }
            }
        }
    }

    suspend fun getTokens(
        wallet: WalletEntity,
        currency: WalletCurrency = settingsRepository.currency,
        refresh: Boolean,
    ): List<AssetsEntity.Token> {
        if (wallet.isGem) return getGemAssets(wallet, currency, refresh).assets.orEmpty().filterIsInstance<AssetsEntity.Token>()
        val safeMode = settingsRepository.isSafeModeEnabled(wallet.network)
        val tronAddress =
            if (wallet.hasPrivateKey && !wallet.testnet) {
                accountRepository.getTronAddress(wallet.id)
            } else {
                null
            }
        val tokens =
            tokenRepository.get(currency, wallet.accountId, wallet.network, refresh, tronAddress)
                ?: return emptyList()
        tokens.firstOrNull()?.let {
            if (wallet.initialized != it.balance.initializedAccount) {
                accountRepository.setInitialized(wallet.id, it.balance.initializedAccount)
            }
        }
        return if (safeMode) {
            tokens.filter { it.verified }.map { AssetsEntity.Token(it) }
        } else {
            tokens.map { AssetsEntity.Token(it) }
        }
    }

    private suspend fun getStaked(
        wallet: WalletEntity,
        tokens: List<AccountTokenEntity>,
        currency: WalletCurrency = settingsRepository.currency,
        refresh: Boolean,
    ): List<AssetsEntity.Staked> {
        if (wallet.isGem) return emptyList()
        val staking = getStaking(wallet, refresh)
        val staked = StakedEntity.create(wallet, staking, tokens, currency, ratesRepository)
        return staked.map { AssetsEntity.Staked(it) }
    }

    private suspend fun getStaking(
        wallet: WalletEntity,
        refresh: Boolean
    ): StakingEntity {
        return stakingRepository.get(
            accountId = wallet.accountId,
            network = wallet.network,
            ignoreCache = refresh,
            initializedAccount = wallet.initialized
        )
    }

    private suspend fun getGemAssets(
        wallet: WalletEntity,
        currency: WalletCurrency,
        refresh: Boolean,
        refreshChains: Set<GemChain>? = null,
    ): AssetsResult = gemAssetsCacheLock.withLock {
        val accountChains = wallet.accounts.mapNotNull { account ->
            GemChain.entries.firstOrNull { it.key == account.chain }
        }.toSet()
        val cacheKey = "${wallet.id}:${currency.code}:${currency.chain.symbol}:${currency.address}:${currency.decimals}"
        val cached = gemAssetsCache[cacheKey].orEmpty().toMutableMap()
        val chains = when {
            refreshChains != null -> accountChains.intersect(refreshChains) + accountChains.filterNot(cached::containsKey)
            refresh -> accountChains
            else -> accountChains.filterNot(cached::containsKey)
        }
        val refreshedChains = mutableSetOf<GemChain>()
        for (chain in chains) {
            val account = wallet.accounts.first { it.chain == chain.key }
            val assets = gemWalletDataSource.getAssets(GemWalletId(wallet.id), chain).getOrNull()
            if (assets == null) {
                continue
            }
            refreshedChains += chain
            val portfolio = gemWalletDataSource.getPortfolio(GemWalletId(wallet.id), chain)
                .getOrNull()
                ?.assets
                ?.associateBy { it.asset.id.value }
                .orEmpty()
            cached[chain] = assets.map { asset ->
                val known = asset.asset.metadata as? GemAssetMetadata.Known
                val token = TokenEntity(
                    blockchain = Blockchain.GEM,
                    address = "${chain.key}:${asset.asset.id.value}",
                    name = known?.name ?: asset.asset.id.value,
                    symbol = known?.symbol ?: asset.asset.id.value,
                    imageUri = android.net.Uri.EMPTY,
                    decimals = known?.decimals ?: nativeDecimals(chain),
                    verification = Verification.none,
                    isRequestMinting = false,
                    isTransferable = true,
                    customPayloadApiUri = null,
                )
                val balance = BalanceEntity(
                    token = token,
                    value = Coins.of(asset.balance.amount.orEmpty(), token.decimals),
                    walletAddress = account.address,
                )
                val fiat = if (currency.code == "USD") {
                    Coins.of(portfolio[asset.asset.id.value]?.balance?.amount.orEmpty(), 2)
                } else {
                    Coins.ZERO
                }
                AssetsEntity.Token(
                    AccountTokenEntity.create(
                        balance = balance,
                        fiatRate = TokenRateEntity(
                            currency = currency,
                            fiat = fiat,
                            rate = Coins.ZERO,
                            rateDiff24h = "",
                        ),
                    ),
                )
            }
        }
        gemAssetsCache[cacheKey] = cached
        return AssetsResult(
            assets = accountChains.flatMap { cached[it].orEmpty() },
            refreshedGemChains = refreshedChains,
        )
    }

    private fun nativeDecimals(chain: GemChain): Int = when (chain) {
        GemChain.Bitcoin -> 8
        GemChain.Ethereum, GemChain.SmartChain -> 18
        GemChain.Solana -> 9
        GemChain.Ton -> 9
    }

    fun getCachedTotalBalance(
        wallet: WalletEntity,
        currency: WalletCurrency,
        sorted: Boolean = false,
    ) = cache.get(wallet, currency, sorted)

    suspend fun requestTotalBalance(
        wallet: WalletEntity,
        currency: WalletCurrency,
        refresh: Boolean = false,
        sorted: Boolean = false,
    ): Coins? {
        val totalBalance = calculateTotalBalance(wallet, currency, refresh, sorted) ?: return null
        cache.set(wallet, currency, sorted, totalBalance)
        return totalBalance
    }

    fun setCachedTotalBalance(
        wallet: WalletEntity, currency: WalletCurrency, sorted: Boolean = false, value: Coins
    ) {
        cache.set(wallet, currency, sorted, value)
    }

    suspend fun getTotalBalance(
        wallet: WalletEntity, currency: WalletCurrency, sorted: Boolean = false
    ) = getCachedTotalBalance(wallet, currency, sorted) ?: requestTotalBalance(
        wallet, currency, sorted
    )

    private suspend fun calculateTotalBalance(
        wallet: WalletEntity,
        currency: WalletCurrency,
        refresh: Boolean,
        sorted: Boolean,
    ): Coins? {
        var assets = getAssets(wallet, currency, refresh).assets ?: return null
        if (sorted) {
            assets = assets.sort(wallet, settingsRepository)
        }
        return assets.sumOfVerifiedFiat()
    }

    override suspend fun getTotalBalance(
        wallet: WalletEntity,
        currency: WalletCurrency
    ): Coins? {
        return getTotalBalance(wallet, currency, false)
    }
}
