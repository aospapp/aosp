/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.adservices.errorlogging;

import android.annotation.NonNull;

import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.stats.StatsdAdServicesLogger;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Class that logs AdServices error/ exception to Statsd. This class internally calls {@link
 * StatsdAdServicesErrorLogger} to log the error to statsd
 */
public class AdServicesErrorLoggerImpl implements AdServicesErrorLogger {
    private static final Object SINGLETON_LOCK = new Object();
    private static volatile AdServicesErrorLoggerImpl sSingleton;
    @NonNull private final Flags mFlags;
    private final StatsdAdServicesErrorLogger mStatsdAdServicesErrorLogger;

    @VisibleForTesting
    protected AdServicesErrorLoggerImpl(
            @NonNull Flags mFlags,
            @NonNull StatsdAdServicesErrorLogger statsdAdServicesErrorLogger) {
        this.mFlags = mFlags;
        this.mStatsdAdServicesErrorLogger = statsdAdServicesErrorLogger;
    }

    /** Returns an instance of {@link StatsdAdServicesLogger}. */
    public static AdServicesErrorLoggerImpl getInstance() {
        if (sSingleton == null) {
            synchronized (SINGLETON_LOCK) {
                if (sSingleton == null) {
                    sSingleton =
                            new AdServicesErrorLoggerImpl(
                                    FlagsFactory.getFlags(), StatsdAdServicesLogger.getInstance());
                }
            }
        }
        return sSingleton;
    }

    /**
     * Creates value {@link AdServicesErrorStats} object and logs AdServices error/exceptions if
     * flag enabled.
     */
    public void logError(
            int errorCode, int ppapiName, @NonNull String className, @NonNull String methodName) {
        if (!mFlags.getAdServicesErrorLoggingEnabled()
                || mFlags.getErrorCodeLoggingDenyList().contains(errorCode)) {
            return;
        }
        AdServicesErrorStats errorData =
                AdServicesErrorStats.builder()
                        .setErrorCode(errorCode)
                        .setPpapiName(ppapiName)
                        .setClassName(className)
                        .setMethodName(methodName)
                        .build();
        mStatsdAdServicesErrorLogger.logAdServicesError(errorData);
    }

    /**
     * Creates value {@link AdServicesErrorStats} object that contains exception information and
     * logs AdServices error/exceptions if flag enabled.
     */
    public void logErrorWithExceptionInfo(@NonNull Throwable tr, int errorCode, int ppapiName) {
        if (!mFlags.getAdServicesErrorLoggingEnabled()
                || mFlags.getErrorCodeLoggingDenyList().contains(errorCode)) {
            return;
        }
        AdServicesErrorStats.Builder builder =
                AdServicesErrorStats.builder().setErrorCode(errorCode).setPpapiName(ppapiName);
        populateExceptionInfo(tr, builder);

        mStatsdAdServicesErrorLogger.logAdServicesError(builder.build());
    }

    private void populateExceptionInfo(
            @NonNull Throwable tr, AdServicesErrorStats.Builder builder) {
        if (tr.getStackTrace().length == 0) {
            return;
        }
        // We just populate the first element of the stack trace
        StackTraceElement element = tr.getStackTrace()[0];
        // Get the class name and is not full qualified.
        String shortClassName = getLastElement(element.getClassName(), '.');
        builder.setClassName(shortClassName);
        builder.setMethodName(element.getMethodName());
        builder.setLineNumber(element.getLineNumber());
        // Get the exception name and is not full qualified.
        String shortExceptionName = getLastElement(tr.getClass().getName(), '.');
        builder.setLastObservedExceptionName(shortExceptionName);
    }

    // Gets the last element of the String based on the delimiter.
    // Example ("com.adservices.Topics", '.')  => "Topics"
    // Example ("Topics", '.')  => "Topics"
    // Example ("", '.')  => ""
    private String getLastElement(String str, int delimiter) {
        if (str.isEmpty()) {
            return str;
        }
        return str.substring(str.lastIndexOf(delimiter) + 1);
    }
}
