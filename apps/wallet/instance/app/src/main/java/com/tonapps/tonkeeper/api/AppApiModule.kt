package com.tonapps.tonkeeper.api

import android.content.pm.ApplicationInfo
import com.tonapps.network.interceptor.LoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appApiModule = module {
    single<LoggingInterceptor.Delegate>(createdAtStart = true) {
		object : LoggingInterceptor.Delegate {
			override fun isEnabled(): Boolean {
				return androidContext().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
			}
		}
    }
}
