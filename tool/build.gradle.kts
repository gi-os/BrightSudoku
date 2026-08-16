plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Required, not optional. The light-sdk plugin registers its annotation
    // processor inside `pluginManager.withPlugin("com.google.devtools.ksp")`, so
    // without KSP applied here it silently generates no
    // com.thelightphone.sdk.generated.LightSdkRegistry — and LightActivity looks
    // that class up reflectively at startup, so the tool crashes the moment it
    // opens. The build succeeds either way, which is what made this easy to miss.
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = rootProject.ext["compileSdk"] as Int

    signingConfigs {
        create("lightsdkDev") {
            storeFile = file("../sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }

        // Sideload key, handed to CI through the environment. Never committed.
        // Android only accepts an update signed by the key that installed the
        // app, so this keystore has to stay the same for the life of the install.
        create("sideload") {
            val keystore = System.getenv("BRIGHTSUDOKU_KEYSTORE_FILE")
            if (!keystore.isNullOrBlank()) {
                storeFile = file(keystore)
                storePassword = System.getenv("BRIGHTSUDOKU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BRIGHTSUDOKU_KEY_ALIAS") ?: "brightsudoku"
                keyPassword = System.getenv("BRIGHTSUDOKU_KEY_PASSWORD")
                    ?: System.getenv("BRIGHTSUDOKU_KEYSTORE_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // Release builds use the sideload key when CI supplies one and the SDK
    // development key otherwise, so a local assembleRelease still works.
    val hasSideloadKey = !System.getenv("BRIGHTSUDOKU_KEYSTORE_FILE").isNullOrBlank()

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        release {
            // Minification is off deliberately.
            //
            // The SDK finds the initial screen, entry point and jobs by name
            // through Class.forName and getMethod on a KSP-generated registry.
            // sdk/client ships consumer rules for that, but the whole startup
            // path depends on reflection surviving R8, and a rule that's merely
            // incomplete fails at launch with nothing failing at build time.
            //
            // The APK is ~26 MB either way — it's dominated by the SDK, CameraX
            // and ML Kit, not by this app's few hundred KB of Kotlin. R8 was
            // buying almost nothing and risking the one thing that has to work.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (hasSideloadKey) "sideload" else "lightsdkDev")
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(project(":sdk:client"))
    testImplementation(libs.kotlin.test)
}
