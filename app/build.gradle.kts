plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.darshj.djproxy"
    compileSdk = 35

    // NDK that ships the vendored hev-socks5-tunnel (.so). r27c is 16 KB-page clean.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "ai.darshj.djproxy"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // libdjproxy-engine.so ships for real devices; x86_64 is a separate emulator build.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Native transport (engine lane): ndk-build over the vendored hev sources + JNI glue.
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    // Release signing is driven ENTIRELY by environment variables — there are NO guessable string
    // fallbacks in source. If DJPROXY_KEYSTORE is unset the release signingConfig stays empty (the
    // release APK is produced unsigned); if the keystore IS named but a password is missing the
    // build fails fast rather than signing with a weak default. The keystore itself is gitignored.
    val releaseKeystore = System.getenv("DJPROXY_KEYSTORE")
    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("DJPROXY_STORE_PASSWORD")
                    ?: throw GradleException("DJPROXY_STORE_PASSWORD must be set for a release signing")
                keyAlias = System.getenv("DJPROXY_KEY_ALIAS")
                    ?: throw GradleException("DJPROXY_KEY_ALIAS must be set for a release signing")
                keyPassword = System.getenv("DJPROXY_KEY_PASSWORD")
                    ?: throw GradleException("DJPROXY_KEY_PASSWORD must be set for a release signing")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
