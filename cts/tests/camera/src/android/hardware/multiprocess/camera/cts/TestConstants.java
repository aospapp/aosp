/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.hardware.multiprocess.camera.cts;

/**
 * Constants used throughout the multi-process unit tests.
 */
public class TestConstants {

    public static final int EVENT_CAMERA_ERROR = -1;
    public static final int EVENT_CAMERA_CONNECT = 1;
    public static final int EVENT_CAMERA_EVICTED = 2;
    public static final int EVENT_CAMERA_AVAILABLE = 3;
    public static final int EVENT_CAMERA_UNAVAILABLE = 4;
    public static final int EVENT_ACTIVITY_RESUMED = 5;
    public static final int EVENT_ACTIVITY_PAUSED = 6;
    public static final int EVENT_CAMERA_ACCESS_PRIORITIES_CHANGED = 7;
    public static final int EVENT_ACTIVITY_TOP_RESUMED_TRUE = 8;
    public static final int EVENT_ACTIVITY_TOP_RESUMED_FALSE = 9;

    public static final String EVENT_CAMERA_ERROR_STR = "error";
    public static final String EVENT_CAMERA_CONNECT_STR = "connect";
    public static final String EVENT_CAMERA_EVICTED_STR = "evicted";
    public static final String EVENT_CAMERA_AVAILABLE_STR = "available";
    public static final String EVENT_CAMERA_UNAVAILABLE_STR = "unavailable";
    public static final String EVENT_ACTIVITY_RESUMED_STR = "resumed";
    public static final String EVENT_ACTIVITY_PAUSED_STR = "paused";
    public static final String EVENT_CAMERA_ACCESS_PRIORITIES_CHANGED_STR =
            "onCameraAccessPrioritiesChanged";
    public static final String EVENT_ACTIVITY_TOP_RESUMED_TRUE_STR = "topResumedTrue";
    public static final String EVENT_ACTIVITY_TOP_RESUMED_FALSE_STR = "topResumedFalse";

    public static final String EVENT_CAMERA_UNKNOWN_STR = "unknown";

    public static final String EXTRA_IGNORE_CAMERA_ACCESS = "ignoreCameraAccess";
    public static final String EXTRA_IGNORE_TOP_ACTIVITY_RESUMED = "ignoreTopActivityResumed";
    public static final String EXTRA_IGNORE_ACTIVITY_PAUSED = "ignoreActivityPaused";

    /**
     * Convert the given error code to a string.
     *
     * @param err error code from {@link TestConstants}.
     * @return string for this error code.
     */
    public static String errToStr(int err) {
        switch(err) {
            case EVENT_CAMERA_ERROR:
                return EVENT_CAMERA_ERROR_STR;
            case EVENT_CAMERA_CONNECT:
                return EVENT_CAMERA_CONNECT_STR;
            case EVENT_CAMERA_EVICTED:
                return EVENT_CAMERA_EVICTED_STR;
            case EVENT_CAMERA_AVAILABLE:
                return EVENT_CAMERA_AVAILABLE_STR;
            case EVENT_CAMERA_UNAVAILABLE:
                return EVENT_CAMERA_UNAVAILABLE_STR;
            case EVENT_ACTIVITY_RESUMED:
                return EVENT_ACTIVITY_RESUMED_STR;
            case EVENT_ACTIVITY_PAUSED:
                return EVENT_ACTIVITY_PAUSED_STR;
            case EVENT_CAMERA_ACCESS_PRIORITIES_CHANGED:
                return EVENT_CAMERA_ACCESS_PRIORITIES_CHANGED_STR;
            case EVENT_ACTIVITY_TOP_RESUMED_TRUE:
                return EVENT_ACTIVITY_TOP_RESUMED_TRUE_STR;
            case EVENT_ACTIVITY_TOP_RESUMED_FALSE:
                return EVENT_ACTIVITY_TOP_RESUMED_FALSE_STR;
            default:
                return EVENT_CAMERA_UNKNOWN_STR + " " + err;
        }
    }

    /**
     * Convert the given array of error codes to an array of strings.
     *
     * @param err array of error codes from {@link TestConstants}.
     * @return string array for the given error codes.
     */
    public static String[] convertToStringArray(int[] err) {
        if (err == null) return null;
        String[] ret = new String[err.length];
        for (int i = 0; i < err.length; i++) {
            ret[i] = errToStr(err[i]);
        }
        return ret;
    }

}
