# djproxy :engine native application config (engine lane).
# APP_ABI is supplied by AGP from android.defaultConfig.ndk.abiFilters.
APP_STL        := c++_static
APP_CPPFLAGS   := -std=c++17 -fexceptions
APP_PLATFORM   := android-24
APP_OPTIM      := release
