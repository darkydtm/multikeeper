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

		private fun Context.isDebuggable(): Boolean {
			return applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
		}
	}
}
