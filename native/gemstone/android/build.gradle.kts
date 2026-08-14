import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

val gemstoneRoot = project.projectDir.resolve("../gemstone")
val workspaceRoot = gemstoneRoot.parentFile
val gemstoneSrc = gemstoneRoot.resolve("android/gemstone/src")
val rustSrcDir = gemstoneRoot.resolve("src")
val cratesDir = workspaceRoot.resolve("crates")
val jniLibsDir = gemstoneSrc.resolve("main/jniLibs")
val generatedKotlinDir = gemstoneSrc.resolve("main/java")
val cargoBuildFlag = if (System.getenv("BUILD_MODE") == "release") "--release" else null
val cargoProfile = if (cargoBuildFlag == null) "debug" else "release"
val nativeLibrary = workspaceRoot.resolve("target/$cargoProfile/libgemstone.so")
val defaultCargoNdkAbis = if (System.getenv("UNIT_TESTS") == "true") {
    "x86_64"
} else {
    "arm64-v8a,armeabi-v7a"
}
val cargoNdkTargets = (System.getenv("GEMSTONE_ANDROID_ABIS") ?: defaultCargoNdkAbis)
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .joinToString(" ") { "-t $it" }

android {
    namespace = "com.gemwallet.gemstone"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        consumerProguardFiles(gemstoneRoot.resolve("android/gemstone/consumer-rules.pro"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            kotlin {
                directories.add(generatedKotlinDir.absolutePath)
            }
            jniLibs {
                directories.add(jniLibsDir.absolutePath)
            }
            manifest.srcFile(gemstoneSrc.resolve("main/AndroidManifest.xml"))
        }
        getByName("androidTest") {
            kotlin {
                directories.add(gemstoneSrc.resolve("androidTest/java").absolutePath)
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val bindgenKotlin = tasks.register<Exec>("bindgenKotlin") {
    description = "Generate Kotlin bindings from gemstone via uniffi"
    workingDir = gemstoneRoot
    inputs.dir(rustSrcDir)
    inputs.dir(cratesDir)
    inputs.file(gemstoneRoot.resolve("Cargo.toml"))
    outputs.dir(generatedKotlinDir.resolve("uniffi"))
    commandLine(
        "/bin/sh", "-c",
        "set -eu; " +
            "cargo build --manifest-path ${workspaceRoot.resolve("Cargo.toml")} -p gemstone ${cargoBuildFlag.orEmpty()}; " +
            "rm -rf ${gemstoneRoot.resolve("generated/kotlin")} ${generatedKotlinDir.resolve("uniffi")}; " +
            "mkdir -p ${gemstoneRoot.resolve("generated/kotlin")} ${generatedKotlinDir}; " +
            "cargo run --manifest-path ${workspaceRoot.resolve("Cargo.toml")} -p uniffi-bindgen -- generate --library --language kotlin --crate gemstone --no-format -o ${gemstoneRoot.resolve("generated/kotlin")} $nativeLibrary; " +
            "cp -Rf ${gemstoneRoot.resolve("generated/kotlin/uniffi")} ${generatedKotlinDir}"
    )
}

val buildCargoNdk = tasks.register<Exec>("buildCargoNdk") {
    description = "Build gemstone native libraries using cargo-ndk"
    workingDir = gemstoneRoot
    inputs.dir(rustSrcDir)
    inputs.dir(cratesDir)
    inputs.file(gemstoneRoot.resolve("Cargo.toml"))
    inputs.property("cargoBuildFlag", cargoBuildFlag.orEmpty())
    outputs.dir(jniLibsDir)
    commandLine("/bin/sh", "-c", "cargo ndk $cargoNdkTargets -o ${jniLibsDir.absolutePath} build --manifest-path ${workspaceRoot.resolve("Cargo.toml")} -p gemstone ${cargoBuildFlag.orEmpty()}")
}

tasks.configureEach {
    if (name.startsWith("lint") || name.startsWith("updateLintBaseline")) {
        enabled = false
    }
    if (name.matches(Regex("(compile|extract|source|javaDoc).*(Debug|Release|Beta).*"))) {
        dependsOn(bindgenKotlin)
    }
    if (name.matches(Regex("merge.*(Debug|Release|Beta).*JniLib.*"))) {
        dependsOn(buildCargoNdk)
    }
}

dependencies {
    api("net.java.dev.jna:jna:5.18.1@aar")
    implementation("androidx.core:core-ktx:1.17.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
