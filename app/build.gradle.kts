plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version : année.mois.numéro (ex. 2026.11.1). Le versionCode en découle : 2026.11.1 -> 202611001.
val appVersion = "2026.11.1"
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (y, m, n) -> y * 100_000 + m * 1_000 + n }

android {
    namespace = "com.printhouse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.printhouse.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Clé de développement : suffit pour installer par USB sur sa propre tablette.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

// Aucune dépendance : Kotlin et les classes d'Android suffisent.
