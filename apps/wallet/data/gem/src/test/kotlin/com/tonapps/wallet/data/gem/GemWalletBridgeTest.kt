package com.tonapps.wallet.data.gem

import org.junit.Assert.assertEquals
import org.junit.Test

class GemWalletBridgeTest {
	@Test
	fun `creates mnemonic words through the injected generator`() {
		val expected = List(12) { "word$it" }
		val words = GemWalletBridge(
			baseDir = "unused",
			passwordProvider = GemPasswordProvider { ByteArray(GEM_KEYSTORE_PASSWORD_SIZE) },
			mnemonicWords = { expected },
		).createMnemonicWords()

		assertEquals(expected, words)
	}

	@Test
	fun `delegates mnemonic validation to the configured validator`() {
		val validWords = List(12) { "word$it" }
		val bridge = GemWalletBridge(
			baseDir = "unused",
			passwordProvider = GemPasswordProvider { ByteArray(GEM_KEYSTORE_PASSWORD_SIZE) },
			mnemonicValidator = { it == validWords },
		)

		assertEquals(true, bridge.isValidMnemonic(validWords))
		assertEquals(false, bridge.isValidMnemonic(List(12) { "invalid" }))
	}
}
