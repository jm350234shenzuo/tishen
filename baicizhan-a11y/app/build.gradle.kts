plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.jm350234shenzuo.bcz.a11y"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jm350234shenzuo.bcz.a11y"
        minSdk = 23
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "bcz123456"
            keyAlias = "bcz"
            keyPassword = "bcz123456"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
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
    compileOnly(project(":xposed-api"))
}
