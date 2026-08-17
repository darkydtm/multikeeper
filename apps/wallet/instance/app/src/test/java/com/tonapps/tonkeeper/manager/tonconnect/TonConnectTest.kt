package com.tonapps.tonkeeper.manager.tonconnect

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TonConnectTest {

	@Test
	fun `accepts repository callback URI shapes`() {
		assertTrue(TonConnect.isSafeReturnUri(Uri.parse("tg://resolve?domain=tonkeeper")))
		assertTrue(TonConnect.isSafeReturnUri(Uri.parse("https://t.me/tonkeeper")))
		assertTrue(TonConnect.isSafeReturnUri(Uri.parse("tc://callback")))
	}

	@Test
	fun `rejects arbitrary callback URI destinations`() {
		assertFalse(TonConnect.isSafeReturnUri(Uri.parse("https://attacker.example/callback")))
		assertFalse(TonConnect.isSafeReturnUri(Uri.parse("tg://join?invite=secret")))
		assertFalse(TonConnect.isSafeReturnUri(Uri.parse("https://t.me")))
		assertFalse(TonConnect.isSafeReturnUri(Uri.parse("http://t.me/tonkeeper")))
		assertFalse(TonConnect.isSafeReturnUri(Uri.parse("https://t.me:443/tonkeeper")))
		assertFalse(TonConnect.isSafeReturnUri(Uri.parse("tc:callback")))
	}

	@Test
	fun `parseReturn keeps only safe callbacks`() {
		assertEquals(
			Uri.parse("https://t.me/tonkeeper"),
			TonConnect.parseReturn("https://t.me/tonkeeper", null)
		)
		assertNull(TonConnect.parseReturn("https://attacker.example/callback", null))
		assertEquals(
			Uri.parse("tg://resolve?domain=tonkeeper"),
			TonConnect.parseReturn("back", Uri.parse("tg://resolve?domain=tonkeeper"))
		)
	}
}
