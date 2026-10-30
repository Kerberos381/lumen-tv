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

    signingConfigs {
        // A personal key kept in the repo so every CI build can update the
        // installed app in place. Keep the repository private (see README).
        create("lumen") {
            storeFile = rootProject.file("keystore/lumen.jks")
            storePassword = System.getenv("LUMEN_STORE_PASSWORD") ?: "lumen-tv-key"
            keyAlias = "lumen"
            keyPassword = System.getenv("LUMEN_KEY_PASSWORD") ?: "lumen-tv-key"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("lumen")
        }
        debug {
            signingConfig = signingConfigs.getByName("lumen")
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
