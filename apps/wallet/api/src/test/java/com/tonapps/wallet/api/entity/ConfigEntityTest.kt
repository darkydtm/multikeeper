package com.tonapps.wallet.api.entity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigEntityTest {

	@Test
	fun `rejects trusted host alternate port`() {
		assertFalse(ConfigEntity.default.copy(tonapiMainnetHost = "https://keeper.tonapi.io:8443").isValid())
	}

	@Test
	fun `accepts trusted host standard HTTPS port`() {
		assertTrue(ConfigEntity.default.copy(tonapiMainnetHost = "https://keeper.tonapi.io:443").isValid())
	}

	@Test
	fun `accepts trusted host without explicit port`() {
		assertTrue(ConfigEntity.default.copy(tonapiMainnetHost = "https://keeper.tonapi.io").isValid())
	}
}
