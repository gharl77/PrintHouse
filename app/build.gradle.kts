plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.printhouse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.printhouse.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.6"
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
