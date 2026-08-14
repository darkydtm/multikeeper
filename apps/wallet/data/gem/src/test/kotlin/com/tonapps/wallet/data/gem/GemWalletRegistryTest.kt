package com.tonapps.wallet.data.gem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GemWalletRegistryTest {
	@Test
	fun `persists and loads schema two wallet metadata without secrets`() {
		val storage = InMemoryRegistryStorage()
		val registry = GemWalletRegistry(storage)
		val wallet = GemWallet(
			walletId = WalletId("wallet"),
			keystoreId = "keystore",
			label = GemWalletLabel("Personal", "custom_wallet", 0xFF123456.toInt()),
			accounts = listOf(
				ChainAccount(
					walletId = WalletId("wallet"),
					chain = Chain.Ethereum,
					address = "0xaddress",
					publicKey = "public-key",
					derivationPath = "m/44'/60'/0'/0/0",
				),
			),
		)

		registry.persist(wallet)

		assertEquals(listOf(wallet), registry.load())
		assertEquals(wallet, registry.load(wallet.walletId))
		assertTrue(storage.value!!.contains("\"schemaVersion\":2"))
		assertTrue(storage.value!!.contains("\"walletId\":\"wallet\""))
		assertFalse(storage.value!!.contains("mnemonic"))
		assertFalse(storage.value!!.contains("privateKey"))

		registry.delete(wallet.walletId)

		assertTrue(registry.load().isEmpty())
		assertNull(storage.value)
	}

	@Test
	fun `loads schema one records without a label`() {
		val storage = InMemoryRegistryStorage(
			"""{"schemaVersion":1,"wallets":[{"walletId":"wallet","keystoreId":"keystore","accounts":[{"chain":"ethereum","address":"address","publicKey":null,"derivationPath":null}]}]}""",
		)

		assertNull(GemWalletRegistry(storage).load().single().label)
	}

	@Test
	fun `rejects unsupported schema`() {
		val storage = InMemoryRegistryStorage("""{"schemaVersion":3,"wallets":[]}""")

		assertThrows(IllegalArgumentException::class.java) {
			GemWalletRegistry(storage).load()
		}
}

	@Test
	fun `rejects unsupported chain`() {
		val storage = InMemoryRegistryStorage(
			"""{"schemaVersion":1,"wallets":[{"walletId":"wallet","keystoreId":"keystore","accounts":[{"chain":"dogecoin","address":"address","publicKey":null,"derivationPath":null}]}]}""",
		)

		assertThrows(IllegalArgumentException::class.java) {
			GemWalletRegistry(storage).load()
		}
	}

	@Test
	fun `does not persist unsupported chain`() {
		val storage = InMemoryRegistryStorage()
		val registry = GemWalletRegistry(storage)
		val wallet = GemWallet(
			walletId = WalletId("wallet"),
			keystoreId = "keystore",
			accounts = listOf(ChainAccount(WalletId("wallet"), Chain.Ton, "address")),
		)

		assertThrows(IllegalStateException::class.java) {
			registry.persist(wallet)
		}
		assertNull(storage.value)
	}

	private class InMemoryRegistryStorage(
		var value: String? = null,
	) : GemWalletRegistryStorage {
		override fun read(): String? = value

		override fun write(value: String): Boolean {
			this.value = value
			return true
		}

		override fun delete(): Boolean {
			value = null
			return true
		}
	}
}
