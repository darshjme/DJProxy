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

# Kotlin metadata for the coroutines/reflection used by the engine lane stays intact by default.
