
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := rtmp 
LOCAL_SRC_FILES := librtmp/librtmp.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := speex
LOCAL_SRC_FILES := speex/libspeex.a
include $(PREBUILT_STATIC_LIBRARY)

# Program
include $(CLEAR_VARS)
LOCAL_MODULE := rtmp-jni 
LOCAL_SRC_FILES :=  rtmpmuxer.c  flvmuxer/xiecc_rtmp.c ring_buffer.c
LOCAL_C_INCLUDES += $(LOCAL_PATH)/librtmp $(LOCAL_PATH)/speex $(LOCAL_PATH) $(LOCAL_PATH)/flvmuxer
LOCAL_LDLIBS := -llog -lz
LOCAL_SHARED_LIBRARIES := rtmp speex
include $(BUILD_SHARED_LIBRARY)

