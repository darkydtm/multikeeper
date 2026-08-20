package com.tonapps.core.flags

import android.content.Context
import android.content.pm.ApplicationInfo

enum class GemWalletEnvironment {
	MAINNET,
	TESTNET,
	;

	companion object {
		private object Key : FeatureKey {
			override val featureKey = "gem_wallet_environment"
		}
		private object TestnetUrlKey : FeatureKey {
			override val featureKey = "gem_wallet_testnet_url"
		}

		fun get(context: Context): GemWalletEnvironment {
			if (!context.isDebuggable()) {
				return MAINNET
			}

			return FeatureManager.getValue(Key, MAINNET.name)
				.let { value -> entries.firstOrNull { it.name == value } ?: MAINNET }
		}

		fun set(context: Context, environment: GemWalletEnvironment) {
			if (context.isDebuggable()) {
				FeatureManager.setFeatureValue(Key, environment.name)
			}
		}

		fun testnetUrl(context: Context): String? {
			return TestnetUrlKey.takeIf { context.isDebuggable() }
				?.let(FeatureManager::optValue)
		}

		fun setTestnetUrl(context: Context, url: String) {
			if (context.isDebuggable()) {
				FeatureManager.setFeatureValue(TestnetUrlKey, url)
			}
		}

		private fun Context.isDebuggable(): Boolean {
			return applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
		}
	}
}
