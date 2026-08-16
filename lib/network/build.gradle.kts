plugins {
    id("target.android.library")
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    testImplementation(libs.junit)

    implementation(projects.lib.log)
    implementation(projects.kmp.async)
}
