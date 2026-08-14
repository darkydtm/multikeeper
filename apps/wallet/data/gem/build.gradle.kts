plugins {
	id("target.android.library")
	alias(libs.plugins.kotlin.serialization)
}

dependencies {
	implementation(projects.gemstone)
	implementation(projects.lib.security)
	implementation(projects.lib.extensions)
	implementation(projects.lib.features)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.okhttp)
	implementation(libs.koin.core)
	testImplementation(libs.junit)
}
