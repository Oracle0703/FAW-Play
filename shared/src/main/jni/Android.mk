LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

OPUS_ROOT := third_party/opus-1.5.2
include $(LOCAL_PATH)/$(OPUS_ROOT)/opus_sources.mk
include $(LOCAL_PATH)/$(OPUS_ROOT)/celt_sources.mk
include $(LOCAL_PATH)/$(OPUS_ROOT)/silk_sources.mk
include $(CLEAR_VARS)
LOCAL_MODULE := diplay_opus
LOCAL_SRC_FILES := opus_microphone_jni.c $(addprefix $(OPUS_ROOT)/,$(OPUS_SOURCES) $(OPUS_SOURCES_FLOAT) $(CELT_SOURCES) $(SILK_SOURCES) $(SILK_SOURCES_FLOAT))
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(OPUS_ROOT)/include $(LOCAL_PATH)/$(OPUS_ROOT)/celt $(LOCAL_PATH)/$(OPUS_ROOT)/silk $(LOCAL_PATH)/$(OPUS_ROOT)/silk/float
LOCAL_CFLAGS := -O2 -std=c99 -DOPUS_BUILD -DVAR_ARRAYS -DHAVE_LRINT -DHAVE_LRINTF
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
