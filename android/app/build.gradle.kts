plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion: String = (project.findProperty("appVersion") as String?) ?: "1.0.0"
val appVersionCode: Int = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.arthuralend.mediaplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arthuralend.mediaplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        // A fixed key kept in the repository so that every build can be
        // installed over the previous one (this is a sideloaded app).
        create("release") {
            storeFile = file("../mediaplayer.keystore")
            storePassword = "mediaplayer"
            keyAlias = "mediaplayer"
            keyPassword = "mediaplayer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        // The torrent engine is an executable shipped as libtorengine.so; it
        // must be extracted to nativeLibraryDir to be runnable.
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("org.videolan.android:libvlc-all:3.6.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
