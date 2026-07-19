# ==========================================================================
# djproxy native build glue  (engine lane)
#
# Builds a single shared object, libdjproxy-engine.so, that links the vendored
# MIT-licensed hev-socks5-tunnel (C / lwIP) as a library plus our thin JNI glue
# (djproxy_jni.cpp) exposing ai.darshj.djproxy.engine.HevBridge.
#
# The vendored sources live under hev-socks5-tunnel/ (see its LICENSE, MIT).
# We reuse hev's own submodule Android.mk files for yaml / lwip / hev-task-system
# so their exact source lists and flags stay authoritative, and we replicate
# hev's shared-library stanza here (renamed, JNI added, hev-jni.c excluded).
# ==========================================================================

TOP_PATH := $(call my-dir)
HEV      := $(TOP_PATH)/hev-socks5-tunnel

# ---- vendored static dependencies (their own, authoritative Android.mk) ----
include $(HEV)/third-part/yaml/Android.mk
include $(HEV)/third-part/lwip/Android.mk
include $(HEV)/third-part/hev-task-system/Android.mk

# ---- our engine shared library: hev core (as a library) + JNI glue ----
LOCAL_PATH := $(TOP_PATH)

# Recursive wildcard, identical to hev's build.mk helper.
rwildcard = $(foreach d,$(wildcard $1*),$(call rwildcard,$d/,$2)$(filter $(subst *,%,$2),$d))

HEV_ALL_SRC := $(call rwildcard,$(HEV)/src/,*.c)
# hev-jni.c is hev's own sample JNI (different package); we ship our own glue.
HEV_LIB_SRC := $(filter-out %/hev-jni.c,$(HEV_ALL_SRC))

include $(CLEAR_VARS)
LOCAL_MODULE := djproxy-engine
LOCAL_SRC_FILES := \
    $(patsubst $(LOCAL_PATH)/%,%,$(HEV_LIB_SRC)) \
    djproxy_jni.cpp
LOCAL_C_INCLUDES := \
    $(HEV)/include \
    $(HEV)/src \
    $(HEV)/src/misc \
    $(HEV)/src/core/include \
    $(HEV)/third-part/yaml/include \
    $(HEV)/third-part/lwip/src/include \
    $(HEV)/third-part/lwip/src/ports/include \
    $(HEV)/third-part/hev-task-system/include
# ENABLE_LIBRARY drops hev's standalone main(); the two *_DEFINED flags match
# hev's own library build (fd_set / socklen_t are provided by the NDK bionic).
LOCAL_CFLAGS += -DENABLE_LIBRARY -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDLIBS  += -llog
# 16 KB page alignment for Android 15 / arm64 devices.
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
