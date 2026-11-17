import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

val signingFile = rootProject.file("key.properties")
val releaseKeys =
    Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
val appVersion =
    Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

android {
    namespace = "com.cbgpu.cbgpu_app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.cbgpu.cbgpu_app"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    signingConfigs {
        if (signingFile.exists())
            create("release") {
                storeFile = rootProject.file(releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
    }
    sourceSets
        .getByName("main")
        .assets
        .directories
        .add(rootProject.file("../assets/fonts").absolutePath)
    sourceSets
        .getByName("main")
        .assets
        .directories
        .add(layout.buildDirectory.dir("native-assets").get().asFile.absolutePath)
    buildTypes {
        release {
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    buildFeatures { buildConfig = true }
    sourceSets
        .getByName("main")
        .jniLibs
        .directories
        .add(layout.buildDirectory.dir("rust-jni").get().asFile.absolutePath)
    packaging { jniLibs.useLegacyPackaging = false }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}

val rustBuild =
    tasks.register("buildRustCore") {
        val manifest = rootProject.file("../native-core/Cargo.toml")
        inputs.files(
            fileTree(manifest.parentFile) { include("src/**", "Cargo.toml", "Cargo.lock") },
        )
        val destination = layout.buildDirectory.dir("rust-jni")
        outputs.dir(destination)
        doLast {
            val sdk =
                System.getenv("ANDROID_HOME")
                    ?: Properties()
                        .apply {
                            rootProject.file("local.properties").inputStream().use { load(it) }
                        }
                        .getProperty("sdk.dir")
            val host =
                if (System.getProperty("os.name").lowercase().contains("mac")) "darwin-x86_64"
                else if (System.getProperty("os.name").lowercase().contains("windows"))
                    "windows-x86_64"
                else "linux-x86_64"
            val suffix = if (host.startsWith("windows")) ".cmd" else ""
            val bin = File(sdk, "ndk/28.2.13676358/toolchains/llvm/prebuilt/$host/bin")
            val cargo = System.getenv("CARGO") ?: "cargo"
            val targetDir = rootProject.layout.buildDirectory.dir("rust-target").get().asFile
            for ((abi, target, clang) in
                listOf(
                    Triple("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android"),
                    Triple("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi"),
                    Triple("x86_64", "x86_64-linux-android", "x86_64-linux-android"),
                )) {
                providers
                    .exec {
                        commandLine(
                            cargo,
                            "build",
                            "--manifest-path",
                            manifest.absolutePath,
                            "--release",
                            "--locked",
                            "--target",
                            target,
                            "--target-dir",
                            targetDir.absolutePath,
                        )
                        environment(
                            "CARGO_TARGET_${target.uppercase().replace('-','_')}_LINKER",
                            File(bin, "${clang}24-clang$suffix").absolutePath,
                        )
                        environment(
                            "CC_${target.replace('-','_')}",
                            File(bin, "${clang}24-clang$suffix").absolutePath,
                        )
                        environment(
                            "AR_${target.replace('-','_')}",
                            File(bin, "llvm-ar${if(host.startsWith("windows"))".exe" else ""}")
                                .absolutePath,
                        )
                    }
                    .result
                    .get()
                    .assertNormalExitValue()
                copy {
                    from(File(targetDir, "$target/release/libcbgpu_core.so"))
                    into(File(destination.get().asFile, abi))
                }
            }
        }
    }

tasks.named("preBuild") { dependsOn(rustBuild) }

tasks.configureEach {
    if (
        name.contains("Release") &&
            (name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("package"))
    )
        doFirst {
            check(signingFile.exists() && releaseKeys.getProperty("storeFile") != null) {
                "Release requires the existing signing keystore; debug fallback is forbidden."
            }
        }
}

val bridgeBuild =
    tasks.register("buildNativeBridge") {
        inputs.files(
            rootProject.file("../native-ui/bridge.ts"),
            rootProject.file("../scripts/build-native-bridge.mts"),
        )
        val output = layout.buildDirectory.dir("native-assets")
        outputs.dir(output)
        doLast {
            providers
                .exec {
                    commandLine(
                        "node",
                        rootProject.file("../scripts/build-native-bridge.mts").absolutePath,
                        output.get().asFile.absolutePath,
                    )
                }
                .result
                .get()
                .assertNormalExitValue()
        }
    }

tasks.named("preBuild") { dependsOn(bridgeBuild) }
