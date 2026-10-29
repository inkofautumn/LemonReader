LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := mobi
LOCAL_SRC_FILES := $(addprefix libmobi/,buffer.c compression.c debug.c index.c memory.c meta.c parse_rawml.c read.c structure.c util.c write.c opf.c xmlwriter.c)
LOCAL_CFLAGS := -std=c99 -DUSE_XMLWRITER -DHAVE_STRDUP -DHAVE_UNISTD_H -DMOBI_INLINE=inline -DPACKAGE_VERSION=\"0.12\"
LOCAL_LDLIBS := -lz
include $(BUILD_SHARED_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := lemon_mobi
LOCAL_SRC_FILES := mobi_bridge.c
LOCAL_SHARED_LIBRARIES := mobi
include $(BUILD_SHARED_LIBRARY)
