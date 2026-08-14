package com.tonapps.wallet.data.gem

import org.junit.Assert.assertThrows
import org.junit.Test
import uniffi.gemstone.GemImportType

class MnemonicHandleTest {
	@Test
	fun `handle can be consumed only once`() {
		val handle = MnemonicHandle(
			GemImportType.MulticoinPhrase(
				words = listOf("abandon"),
				chains = listOf("ethereum"),
			),
		)

		handle.consumeImportType()

		assertThrows(IllegalStateException::class.java) {
			handle.consumeImportType()
		}
	}
}
