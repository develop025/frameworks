# Copyright (C) 2011 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
ifeq ($(strip $(MTK_AUTO_TEST)), yes)

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := android.test.runner\
                        mediatek-framework

LOCAL_PACKAGE_NAME := SystemUITests

# sign this with platform cert, so this test is allowed to inject key events into
# UI it doesn't own. This is necessary to allow screenshots to be taken
LOCAL_CERTIFICATE := platform

LOCAL_INSTRUMENTATION_FOR := SystemUI

include $(BUILD_PACKAGE)

endif