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

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__CONSENT_REVOKED_ERROR;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.database.sqlite.SQLiteException;

import com.android.adservices.service.Flags;
import com.android.adservices.service.stats.StatsdAdServicesLogger;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AdServicesErrorLoggerImplTest {
    private AdServicesErrorLoggerImpl mErrorLogger;

    // Constants used for tests
    private static final String CLASS_NAME = "TopicsService";
    private static final String METHOD_NAME = "getTopics";
    private static final int PPAPI_NAME = AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS;
    private static final int LINE_NUMBER = 11;
    private static final String SQ_LITE_EXCEPTION = "SQLiteException";

    @Mock private Flags mFlags;
    @Mock StatsdAdServicesLogger mStatsdLoggerMock;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mErrorLogger = new AdServicesErrorLoggerImpl(mFlags, mStatsdLoggerMock);
        doReturn(ImmutableList.of()).when(mFlags).getErrorCodeLoggingDenyList();
    }

    @Test
    public void testLogError_errorLoggingFlagDisabled() {
        doReturn(false).when(mFlags).getAdServicesErrorLoggingEnabled();

        mErrorLogger.logError(
                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__CONSENT_REVOKED_ERROR, PPAPI_NAME,
                CLASS_NAME, METHOD_NAME);

        verify(mStatsdLoggerMock, never()).logAdServicesError(any());
    }

    @Test
    public void testLogError_errorLoggingFlagEnabled_errorCodeLoggingDenied() {
        doReturn(true).when(mFlags).getAdServicesErrorLoggingEnabled();
        doReturn(ImmutableList.of(AD_SERVICES_ERROR_REPORTED__ERROR_CODE__CONSENT_REVOKED_ERROR))
                .when(mFlags)
                .getErrorCodeLoggingDenyList();

        mErrorLogger.logError(
                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__CONSENT_REVOKED_ERROR, PPAPI_NAME,
                CLASS_NAME, METHOD_NAME);

        verify(mStatsdLoggerMock, never()).logAdServicesError(any());
    }

    @Test
    public void testLogError_errorLoggingFlagEnabled() {
        doReturn(true).when(mFlags).getAdServicesErrorLoggingEnabled();
        mErrorLogger.logError(
                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__CONSENT_REVOKED_ERROR,
                PPAPI_NAME,
                CLASS_NAME,
                METHOD_NAME);

        AdServicesErrorStats stats =
                AdServicesErrorStats.builder()
                        .setErrorCode(AD_SERVICES_ERROR_REPORTED__ERROR_CODE__CONSENT_REVOKED_ERROR)
                        .setPpapiName(PPAPI_NAME)
                        .setClassName(CLASS_NAME)
                        .setMethodName(METHOD_NAME)
                        .build();
        verify(mStatsdLoggerMock).logAdServicesError(eq(stats));
    }

    @Test
    public void testLogErrorWithExceptionInfo_errorLoggingFlagDisabled() {
        doReturn(false).when(mFlags).getAdServicesErrorLoggingEnabled();

        mErrorLogger.logErrorWithExceptionInfo(
                new Exception(),
                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION,
                PPAPI_NAME);

        verify(mStatsdLoggerMock, never()).logAdServicesError(any());
    }

    @Test
    public void testLogErrorWithExceptionInfo_errorLoggingFlagEnabled_errorCodeLoggingDenied() {
        doReturn(true).when(mFlags).getAdServicesErrorLoggingEnabled();
        doReturn(ImmutableList.of(AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION))
                .when(mFlags)
                .getErrorCodeLoggingDenyList();

        mErrorLogger.logErrorWithExceptionInfo(
                new Exception(),
                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION,
                PPAPI_NAME);

        verify(mStatsdLoggerMock, never()).logAdServicesError(any());
    }

    @Test
    public void testLogErrorWithExceptionInfo_errorLoggingFlagEnabled() {
        doReturn(true).when(mFlags).getAdServicesErrorLoggingEnabled();

        Exception exception = createSQLiteException(CLASS_NAME, METHOD_NAME, LINE_NUMBER);

        mErrorLogger.logErrorWithExceptionInfo(
                exception,
                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION,
                PPAPI_NAME);

        AdServicesErrorStats stats =
                AdServicesErrorStats.builder()
                        .setErrorCode(
                                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION)
                        .setPpapiName(PPAPI_NAME)
                        .setClassName(CLASS_NAME)
                        .setMethodName(METHOD_NAME)
                        .setLineNumber(LINE_NUMBER)
                        .setLastObservedExceptionName(SQ_LITE_EXCEPTION)
                        .build();
        verify(mStatsdLoggerMock).logAdServicesError(eq(stats));
    }

    @Test
    public void testLogErrorWithExceptionInfo_fullyQualifiedClassName_errorLoggingFlagEnabled() {
        doReturn(true).when(mFlags).getAdServicesErrorLoggingEnabled();

        String fullClassName = "com.android.adservices.topics.TopicsService";
        Exception exception = createSQLiteException(fullClassName, METHOD_NAME, LINE_NUMBER);

        mErrorLogger.logErrorWithExceptionInfo(
                exception,
                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION,
                PPAPI_NAME);

        AdServicesErrorStats stats =
                AdServicesErrorStats.builder()
                        .setErrorCode(
                                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION)
                        .setPpapiName(PPAPI_NAME)
                        .setClassName(CLASS_NAME)
                        .setMethodName(METHOD_NAME)
                        .setLineNumber(LINE_NUMBER)
                        .setLastObservedExceptionName(SQ_LITE_EXCEPTION)
                        .build();
        verify(mStatsdLoggerMock).logAdServicesError(eq(stats));
    }

    @Test
    public void testLogErrorWithExceptionInfo_emptyClassName_errorLoggingFlagEnabled() {
        doReturn(true).when(mFlags).getAdServicesErrorLoggingEnabled();

        Exception exception = createSQLiteException(/* className = */ "", METHOD_NAME, LINE_NUMBER);

        mErrorLogger.logErrorWithExceptionInfo(
                exception,
                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION,
                PPAPI_NAME);

        AdServicesErrorStats stats =
                AdServicesErrorStats.builder()
                        .setErrorCode(
                                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION)
                        .setPpapiName(PPAPI_NAME)
                        .setMethodName(METHOD_NAME)
                        .setLineNumber(LINE_NUMBER)
                        .setLastObservedExceptionName(SQ_LITE_EXCEPTION)
                        .build();
        verify(mStatsdLoggerMock).logAdServicesError(eq(stats));
    }

    Exception createSQLiteException(String className, String methodName, int lineNumber) {
        StackTraceElement[] stackTraceElements =
                new StackTraceElement[] {
                    new StackTraceElement(className, methodName, "file", lineNumber)
                };

        Exception exception = new SQLiteException();
        exception.setStackTrace(stackTraceElements);
        return exception;
    }
}
