package com.tonapps.wallet.data.gem

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class GemLocaleTest {
	@Test
	fun `normalizes locales accepted by gem backend`() {
		assertEquals("en", Locale.forLanguageTag("en-US").toGemLocale())
		assertEquals("pt-BR", Locale.forLanguageTag("pt-BR").toGemLocale())
		assertEquals("zh-Hans", Locale.forLanguageTag("zh-Hans").toGemLocale())
	}
}
