/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.car.internal.util;

import android.annotation.ChecksSdkIntAtLeast;
import android.car.Car;
import android.car.PlatformVersion;
import android.car.PlatformVersionMismatchException;
import android.os.Build;

/**
 * Utility class for platform and car API version check.
 *
 * @hide
 */
public final class VersionUtils {

    /**
     * Asserts if the current platform version is at least expected platform version.
     *
     * @throws PlatformVersionMismatchException if current platform version is not equal to or
     * greater than expected platform version.
     *
     * @deprecated Use version specific call for example {@link #assertPlatformVersionAtLeastU()}.
     */
    public static void assertPlatformVersionAtLeast(PlatformVersion expectedPlatformApiVersion) {
        if (!isPlatformVersionAtLeast(expectedPlatformApiVersion)) {
            throw new PlatformVersionMismatchException(expectedPlatformApiVersion);
        }
    }

    /**
     * Asserts if the current platform version is at least {@code UPSIDE_DOWN_CAKE}.
     *
     * @throws PlatformVersionMismatchException if current platform version is not equal to or
     * greater than expected platform version.
     */
    public static void assertPlatformVersionAtLeastU() {
        assertPlatformVersionAtLeast(PlatformVersion.VERSION_CODES.UPSIDE_DOWN_CAKE_0);
    }

    /**
     * Checks if the current platform version is at least expected platform version.
     *
     * <p>{@link #isPlatformVersionAtLeast(PlatformVersion)} is a general call which can take any
     * platform version object. Lint check needs in the annotation which version is supported.
     * Example: {@code com.android.modules.utils.build.SdkLevel} class.
     *
     * @deprecated Use version specific call for example {@link #isPlatformVersionAtLeastU()}.
     */
    @Deprecated
    public static boolean isPlatformVersionAtLeast(PlatformVersion expectedPlatformVersion) {
        PlatformVersion currentPlatformVersion = Car.getPlatformVersion();
        return currentPlatformVersion.isAtLeast(expectedPlatformVersion);
    }

    /**
     * Checks if the current platform version is at least {@code UPSIDE_DOWN_CAKE}.
     *
     * <p>The method will be used by the lint check to make sure that calls are properly version
     * guarded.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, codename = "UpsideDownCake")
    public static boolean isPlatformVersionAtLeastU() {
        return isPlatformVersionAtLeast(PlatformVersion.VERSION_CODES.UPSIDE_DOWN_CAKE_0);
    }

    private VersionUtils() {
        throw new UnsupportedOperationException("contains only static method methods");
    }
}
