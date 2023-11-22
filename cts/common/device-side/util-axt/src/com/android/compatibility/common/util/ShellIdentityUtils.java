/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.compatibility.common.util;

import android.app.UiAutomation;

import androidx.test.InstrumentationRegistry;

/**
 * Provides utility methods to invoke system and privileged APIs as the shell user.
 */
public class ShellIdentityUtils {

    /**
     * Utility interface to invoke a method against the target object.
     *
     * @param <T> the type returned by the invoked method.
     * @param <U> the type of the object against which the method is invoked.
     */
    public interface ShellPermissionMethodHelper<T, U> {
        /**
         * Invokes the method against the target object.
         *
         * @param targetObject the object against which the method should be invoked.
         * @return the result of the invoked method.
         */
        T callMethod(U targetObject);
    }

    /**
     * Utility interface to invoke a method against the target object.
     *
     * @param <U> the type of the object against which the method is invoked.
     */
    public interface ShellPermissionMethodHelperNoReturn<U> {
        /**
         * Invokes the method against the target object.
         *
         * @param targetObject the object against which the method should be invoked.
         */
        void callMethod(U targetObject);
    }

    /**
     * Invokes the specified method on the targetObject as the shell user. The method can be invoked
     * as follows:
     *
     * {@code ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
     *        (tm) -> tm.getDeviceId());}
     */
    public static <T, U> T invokeMethodWithShellPermissions(U targetObject,
            ShellPermissionMethodHelper<T, U> methodHelper) {
        final UiAutomation uiAutomation =
                InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity();
            return methodHelper.callMethod(targetObject);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Invokes the specified method on the targetObject as the shell user. The method can be invoked
     * as follows:
     *
     * {@code ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
     *        (tm) -> tm.getDeviceId());}
     */
    public static <U> void invokeMethodWithShellPermissionsNoReturn(
            U targetObject, ShellPermissionMethodHelperNoReturn<U> methodHelper) {
        final UiAutomation uiAutomation =
                InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity();
            methodHelper.callMethod(targetObject);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    /**
     * Utility interface to invoke a static method.
     *
     * @param <T> the type returned by the invoked method.
     */
    public interface StaticShellPermissionMethodHelper<T> {
        /**
         * Invokes the static method.
         *
         * @return the result of the invoked method.
         */
        T callMethod();
    }

    /**
     * Invokes the specified static method as the shell user. This method can be invoked as follows:
     *
     * {@code ShellIdentityUtils.invokeStaticMethodWithShellPermissions(Build::getSerial));}
     */
    public static <T> T invokeStaticMethodWithShellPermissions(
            StaticShellPermissionMethodHelper<T> methodHelper) {
        final UiAutomation uiAutomation =
                InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity();
            return methodHelper.callMethod();
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }
}
