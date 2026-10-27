plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.remoteandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.remoteandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign release with the debug key so `build.sh release` produces a
            // directly installable APK (this is a personal sideload tool, not a
            // Play Store release).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "META-INF/versions/**",
                    "META-INF/INDEX.LIST",
                    "META-INF/INFO",
                )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    implementation("com.google.zxing:core:3.5.3")
}
