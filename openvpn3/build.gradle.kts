// Self-contained OpenVPN3 core module — builds libovpn3.so (the official OpenVPN3 C++ client, from
// ics-openvpn's vendored core: openssl + asio + lzo + lz4 + fmt) and the SWIG-generated
// net.openvpn.ovpn3 Java bindings, so the app's ovpn3 lane can connect VPN Gate OpenVPN servers
// (inline PKI + tls-auth/tls-crypt + NCP — everything minivpn couldn't do). GPL-2.0 (ics-openvpn).
// Uses THIS repo's toolchain (NDK r27 / SDK 35 / SWIG 4.2.1) rather than ics-openvpn's macOS-oriented
// build.gradle.

import org.gradle.api.file.DirectoryProperty

plugins {
    id("com.android.library")
}

val swigExe = "D:/AI/swig-extract/swigwin-4.2.1/swig.exe"
val cppDir = "${projectDir}/src/main/cpp"

android {
    namespace = "net.openvpn.ovpn3"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 21
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        externalNativeBuild {
            cmake {
                // Build ONLY libovpn3.so (the OpenVPN3 C++ core). The CMakeLists also defines legacy
                // OpenVPN-2.x targets (openvpn/libovpnexec.so/ovpnutil/osslspeedtest) which we don't use
                // and which don't compile under NDK r27's stricter clang (basename implicit-decl) — the
                // `targets` filter skips them; ovpn3's static link deps (crypto/ssl/lzo/lz4/fmt) still build.
                targets += "ovpn3"
                // USE_OPENSSL keeps NCP/tls-crypt strong; STL static so no libc++_shared dep.
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DUSE_OPENSSL=1",
                    "-DSWIG_EXECUTABLE=$swigExe",
                    "-DSWIG_DIR=D:/AI/swig-extract/swigwin-4.2.1/Lib",
                )
                cppFlags += "-std=c++17 -fexceptions -frtti"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures { buildConfig = false }
    // The vendored core ships its own licenses/notices; keep the .so, drop duplicate meta.
    packaging { jniLibs { keepDebugSymbols += "**/*.so" } }
}
// The net.openvpn.ovpn3 SWIG Java is committed under src/main/java (generated once from ovpncli.i by
// CMake's SWIG step). CMake still runs SWIG during the native build to produce ovpncli_wrap.cxx for
// libovpn3.so; the Java bindings are stable for this pinned ovpncli.i, so no gradle-side gen is needed.
