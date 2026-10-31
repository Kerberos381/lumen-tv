plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes the run number so every build installs over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.lumen.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.lumen.launcher"
        minSdk = 28
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // The signing key never lives in the repo. CI decodes it from the
    // LUMEN_KEYSTORE_B64 secret; locally, put it at keystore/lumen.p12 (ignored by git).
    val keystore = rootProject.file(System.getenv("LUMEN_KEYSTORE") ?: "keystore/lumen.p12")
    val lumenKey = if (keystore.exists()) {
        signingConfigs.create("lumen") {
            storeFile = keystore
            storeType = "pkcs12"
            storePassword = System.getenv("LUMEN_STORE_PASSWORD")
            keyAlias = "lumen"
            keyPassword = System.getenv("LUMEN_KEY_PASSWORD")
        }
    } else null

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the key, local release builds use the debug key and cannot update a CI build.
            signingConfig = lumenKey ?: signingConfigs.getByName("debug")
        }
        debug {
            lumenKey?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Lint findings are reviewed by hand; they must not block the APK.
        abortOnError = false
        checkReleaseBuilds = false
    }
}
