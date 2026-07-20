# DJProxy R8 / ProGuard keep rules.
#
# The transport crosses two boundaries R8 must not rename or strip:
#   1. The JNI boundary to libdjproxy-engine.so — native symbol names are derived from the Java
#      class + method names at runtime (System.loadLibrary + external fun), so renaming them breaks
#      the lookup with UnsatisfiedLinkError.
#   2. The AIDL Binder between the main process and the isolated :engine process.

# --- JNI: keep every native method's owning class + name, and the HevBridge object wholesale. ---
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class ai.darshj.djproxy.engine.HevBridge { *; }
-keep class ai.darshj.djproxy.engine.DefaultHevBridge { *; }

# --- AIDL Binder surface (interface + generated Stub/Proxy) across the :engine process boundary. ---
-keep class ai.darshj.djproxy.engine.IEngineControl { *; }
-keep class ai.darshj.djproxy.engine.IEngineControl$* { *; }

# --- guardianproject tor-android (embedded Tor): the tor daemon in libtor.so reaches BACK into
#     org.torproject.jni.TorService via JNI GetFieldID / GetMethodID by NAME — notably the long
#     field `torConfiguration` read/freed by the native mainConfiguration* methods. R8 renames or
#     strips those members in release, so the native call throws
#     `NoSuchFieldError: no "J" field "torConfiguration"` the instant Tor starts (release-only crash;
#     debug builds are unminified so it never showed there). Keeping only `native <methods>` above is
#     NOT enough — the JNI-accessed FIELDS and helper methods are plain Java. Keep the whole TorService
#     plus its inner classes, and the jtorctl control-port client we talk to it with. ---
-keep class org.torproject.jni.TorService { *; }
-keep class org.torproject.jni.TorService$* { *; }
-keep class net.freehaven.tor.control.** { *; }

# Kotlin metadata for the coroutines/reflection used by the engine lane stays intact by default.
