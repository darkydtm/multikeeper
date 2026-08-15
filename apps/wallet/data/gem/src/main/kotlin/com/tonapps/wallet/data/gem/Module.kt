package com.tonapps.wallet.data.gem

import android.content.Context
import android.os.Build
import com.tonapps.core.flags.GemWalletEnvironment
import com.tonapps.security.Security
import com.tonapps.security.SecurityStorageBox
import java.util.Locale
import okhttp3.OkHttpClient
import org.koin.core.qualifier.named
import org.koin.dsl.module
import uniffi.gemstone.AlienProvider
import uniffi.gemstone.GemGateway
import uniffi.gemstone.GemPreferences

private const val GEM_HTTP_CLIENT = "gemHttpClient"
private const val GEM_DEVICE_STORAGE_KEY_ALIAS = "_com_tonapps_gem_device_master_key_"
private const val GEM_DEVICE_STORAGE_NAME = "gem_device"
private const val GEM_KEYSTORE_STORAGE_KEY_ALIAS = "_com_tonapps_gem_keystore_master_key_"
private const val GEM_KEYSTORE_STORAGE_NAME = "gem_keystore"
private const val GEM_KEYSTORE_STORAGE = "gemKeystoreStorage"
private const val GEM_GATEWAY_STORAGE = "gemGatewayStorage"
private const val GEM_GATEWAY_SECURE_STORAGE = "gemGatewaySecureStorage"
private const val GEM_GATEWAY_PREFERENCES = "gemGatewayPreferences"
private const val GEM_GATEWAY_SECURE_PREFERENCES = "gemGatewaySecurePreferences"
private const val GEM_NODE_TOKEN_STORAGE = "gemNodeTokenStorage"

internal fun Locale.toGemLocale(): String {
	val tag = toLanguageTag()
	if (tag == "pt-BR" || tag == "pt_BR") {
		return "pt-BR"
	}
	if (language == "zh") {
		return "$language-${script.ifEmpty { "Hans" }}"
	}
	return language
}

val gemModule = module {
	// Use a minimal isolated client because the existing DI graph exposes no configured OkHttp binding.
	single(named(GEM_HTTP_CLIENT)) { OkHttpClient.Builder().build() }

	single {
		val context: Context = get()
		GemBackendEnvironment.forBuild(
			isDebug = Security.isDebuggable(context),
			debugEnvironment = when (GemWalletEnvironment.get(context)) {
				GemWalletEnvironment.MAINNET -> GemBackendEnvironment.MAINNET
				GemWalletEnvironment.TESTNET -> GemBackendEnvironment.TESTNET
			},
		)
	}
	single<SecurityStorageBox> {
		Security.pref(get(), GEM_DEVICE_STORAGE_KEY_ALIAS, GEM_DEVICE_STORAGE_NAME)
	}
	single(named(GEM_KEYSTORE_STORAGE)) {
		Security.pref(get(), GEM_KEYSTORE_STORAGE_KEY_ALIAS, GEM_KEYSTORE_STORAGE_NAME)
	}
	single(named(GEM_GATEWAY_STORAGE)) {
		Security.pref(get(), "_com_tonapps_gem_gateway_key_", "gem_gateway")
	}
	single(named(GEM_GATEWAY_SECURE_STORAGE)) {
		Security.pref(get(), "_com_tonapps_gem_gateway_secure_key_", "gem_gateway_secure")
	}
	single(named(GEM_NODE_TOKEN_STORAGE)) {
		Security.pref(get(), "_com_tonapps_gem_node_token_key_", "gem_node_token")
	}
	single<GemPreferences>(named(GEM_GATEWAY_PREFERENCES)) {
		GemstonePreferences(get(named(GEM_GATEWAY_STORAGE)))
	}
	single<GemPreferences>(named(GEM_GATEWAY_SECURE_PREFERENCES)) {
		GemstonePreferences(get(named(GEM_GATEWAY_SECURE_STORAGE)))
	}
	single<GemPasswordStorage> {
		GemKeystorePasswordStorage(get(named(GEM_KEYSTORE_STORAGE)))
	}
	single<GemPasswordProvider> {
		GemKeystorePasswordProvider(get())
	}
	single<GemKeystoreFactory> {
		GemstoneKeystoreFactory(get<Context>().dataDir.absolutePath)
	}
	single {
		GemWalletBridge(
			baseDir = get<Context>().dataDir.absolutePath,
			passwordProvider = get(),
		)
	}
	single<GemKeystoreDeleter> { get<GemWalletBridge>() }
	single { GemDeviceIdentity(get()) }
	single<GemDeviceIdentityProvider> { get<GemDeviceIdentity>() }
	single<GemRequestSigner> { GemDeviceAuthSigner(storage = get(), deviceIdentity = get()) }
	single {
		GemBackendClient(
			httpClient = get(named(GEM_HTTP_CLIENT)),
			signer = get(),
			environment = get(),
		)
	}
	single<GemDeviceBackend> { get<GemBackendClient>() }
	single<GemBackendReader> { get<GemBackendClient>() }
	single<GemDeviceTokenBackend> { get<GemBackendClient>() }
	single {
		GemNodeTokenProvider(
			backend = get(),
			storage = get(named(GEM_NODE_TOKEN_STORAGE)),
		)
	}
	single<AlienProvider> {
		GemstoneAlienProvider(
			client = get(named(GEM_HTTP_CLIENT)),
			tokenProvider = get(),
		)
	}
	single {
		GemGateway(
			provider = get<AlienProvider>(),
			preferences = get(named(GEM_GATEWAY_PREFERENCES)),
			securePreferences = get(named(GEM_GATEWAY_SECURE_PREFERENCES)),
			apiUrl = get<GemBackendEnvironment>().baseUrl,
		)
	}
	single<GemGatewayFactory> { GemstoneGatewayFactory(get()) }
	single {
		GemstoneTransactionBridge(
			gatewayFactory = get(),
			keystoreFactory = get(),
			passwordProvider = get(),
			draftAdapter = GemstoneTransactionDraftAdapter(get()),
		)
	}
	single<GemBalanceReader> { GemstoneBalanceReader(get()) }
	single<GemSubscriptionBackend> { get<GemBackendClient>() }
	single {
		val context: Context = get()
		GemDeviceMetadata(
			platform = "android",
			platformStore = "googlePlay",
			os = "android ${Build.VERSION.RELEASE}",
			model = Build.MODEL,
			token = "",
			locale = Locale.getDefault().toGemLocale(),
			version = context.packageManager
				.getPackageInfo(context.packageName, 0)
				.versionName
				.orEmpty(),
			currency = "USD",
			isPushEnabled = false,
			subscriptionsVersion = 1,
		)
	}
	single {
		GemDeviceRegistrationCoordinator(
			identity = get<GemDeviceIdentityProvider>(),
			backend = get<GemDeviceBackend>(),
			metadata = get<GemDeviceMetadata>(),
		)
	}
	single { GemWalletRegistry(get<SecurityStorageBox>()) }
	single { GemTokenRepository(get<SecurityStorageBox>()) }
	single { CoinGeckoAttributesResolver(client = get(named(GEM_HTTP_CLIENT))) }
	single {
		GemWalletDataSource(
			backend = get(),
			walletRegistry = get(),
			balanceReader = get(),
			transactionBridge = get(),
			tokenRepository = get(),
		)
	}
	single<WalletDataSource> { get<GemWalletDataSource>() }
	single { GemSendCoordinator(get()) }
	single {
		GemWebSocketClient(
			client = get(named(GEM_HTTP_CLIENT)),
			signer = get(),
			environment = get(),
			priceAssets = listOf("bitcoin", "ethereum", "smartchain", "solana"),
		)
	}
	single { GemSubscriptionRepository(get<GemSubscriptionBackend>()) }
	single { GemAuthoritativeRefreshStore(get<GemBackendReader>()) }
	single<GemAuthoritativeRefresh> { get<GemAuthoritativeRefreshStore>() }
	single {
		GemRuntimeCoordinator(
			deviceRegistration = get(),
			walletRegistry = get(),
			keystoreDeleter = get(),
			subscriptionRepository = get(),
			webSocketClient = get(),
			refresh = get(),
		)
	}
}
