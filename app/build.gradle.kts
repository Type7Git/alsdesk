plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath: String? = System.getenv("SYNC_KEYSTORE_PATH")

android {
    namespace = "au.type7.alsdesksync"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.type7.alsdesksync"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SYNC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SYNC_KEY_ALIAS")
                keyPassword = System.getenv("SYNC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Opens the desk full-screen in a Trusted Web Activity (Chrome), so Google sign-in keeps working.
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.5.0")
}
