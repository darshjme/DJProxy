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
        // v3 compat matrix (DESIGN_V3 §7.1): one universal APK from a rooted Kindle Fire on API 21
        // up to the newest retail Android. Every API-gated call in the codebase is guarded down to
        // 21 (see DjVpnService/EngineService/TunBuilder/VpnController Build.VERSION.SDK_INT checks);
        // CredentialStore degrades to session-only credentials below API 23 (no AndroidKeyStore GCM).
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Ship all four ABIs so one universal APK runs on physical ARM phones AND on
        // x86/x86_64 Android emulators (LDPlayer, BlueStacks, Android Studio AVD, Genymotion).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        // Diagnostic-report recipient (see MailIntentFactory). This official build ships the owner's
        // inbox; a third-party fork of this MIT source should blank it (or set its own) so a rebranded
        // binary never silently mails reports to the original owner. Overridable per build type.
        buildConfigField("String", "DIAG_RECIPIENT", "\"darshjme@gmail.com\"")
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
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        // False positive for a Compose-only app: registerForActivityResult is invoked on a
        // ComponentActivity (activity-compose 1.9.3), which correctly implements ActivityResultCaller.
        // This app has NO androidx.fragment usage, so the "upgrade Fragment to >=1.3.0" requirement
        // does not apply. Every other lint check stays fatal for release (checkReleaseBuilds default).
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

dependencies {
    // ovpnengine lane: the userspace OpenVPN→local-SOCKS5 engine (ooni/minivpn + wireguard netstack +
    // go-socks5, gomobile-bound). Lets a VPN Gate / OpenVPN server be used AS a proxy the existing hev
    // tunnel routes through — same shape as the embedded Tor lane. 4 ABIs, JNI .so inside the .aar.
    implementation(files("libs/ovpnsocks.aar"))
    // ovpn3 lane: the OFFICIAL OpenVPN3 C++ core (ics-openvpn's vendored core → libovpn3.so +
    // net.openvpn.ovpn3 SWIG bindings, GPL-2.0). Handles inline <ca>/<cert>/<key> + tls-auth/tls-crypt +
    // NCP that minivpn lacked; establishes DJProxy's tun directly for VPN Gate OpenVPN servers.
    implementation(project(":openvpn3"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Feature-lane wiring seam (DESIGN_V3 §9.1): each feature lane (location/hotspot/diagnostics)
    // ships an androidx.startup Initializer; compat is the single writer that registers them as
    // <meta-data> under InitializationProvider in AndroidManifest.xml. Core/ui never depend on this.
    implementation("androidx.startup:startup-runtime:1.2.0")

    // Optional dependency for the location lane's T1 mock-location tier (DESIGN_V3 §5.3):
    // FusedLocationProviderClient.setMockMode, guarded at runtime by
    // CapabilityDetector.hasPlayServices() and degraded to plain LocationManager when Play services
    // are absent (de-Googled ROMs, most emulators without a Google image). compat owns the Gradle
    // file so it is the one place this optional dep is declared; location lane never edits build files.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // qr lane: CameraX preview/analysis for QrCameraScanner + a de-Googled, offline-capable QR
    // decode/encode path (ZXing core only — NO ML Kit / Play Services barcode scanning, per
    // DESIGN_V4 platform §8). camera-view supplies PreviewView; camera-lifecycle binds to a
    // LifecycleOwner so the qr lane never manages the CameraX session lifecycle by hand.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.zxing:core:3.5.3")

    // ui lane: androidx.graphics-shapes backs ConnectRing's shape-morphing squircle (Material 3
    // Expressive "large expressive shape as container" motion — DESIGN_V4 UI research).
    implementation("androidx.graphics:graphics-shapes:1.0.1")

    // tor lane: embedded Tor (guardianproject tor-android) + jtorctl control-port client, wired
    // through tor.TorGateway/TorController -> the EXISTING VpnController.apply(socks5://127.0.0.1:9050)
    // seam, zero core edits. DESIGN_V4 names 0.4.8.7, but that tag is only published on the
    // guardianproject gpmaven repo (https://raw.githubusercontent.com/guardianproject/gpmaven/master),
    // which is NOT resolvable here: settings.gradle.kts pins
    // repositoriesMode = FAIL_ON_PROJECT_REPOS with only google()+mavenCentral() declared, and
    // adding a repository is outside this lane's ownership (platform owns only this file +
    // AndroidManifest.xml, not settings.gradle.kts). 0.4.7.14 is the newest tor-android build
    // actually published to Maven Central (verified via search.maven.org) and its bundled
    // AndroidManifest declares minSdkVersion 16, targetSdkVersion 33 — fully compatible with this
    // app's minSdk 21/targetSdk 35, so no <uses-sdk tools:overrideLibrary> is needed. jtorctl
    // 0.4.5.7 (the version DESIGN_V4 names) IS on Maven Central verbatim.
    implementation("info.guardianproject:tor-android:0.4.7.14")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    // REQUIRED by org.torproject.jni.TorService: it uses androidx LocalBroadcastManager in onCreate(),
    // but tor-android does NOT pull it transitively (LocalBroadcastManager is deprecated and no longer a
    // default androidx dep). Without this, enabling Tor crashed instantly with NoClassDefFoundError:
    // LocalBroadcastManager (confirmed on-device: TorService.onCreate -> LocalBroadcastManager).
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
