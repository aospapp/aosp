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
package com.android.adservices.common;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.annotation.Nullable;
import android.app.Instrumentation;
import android.content.pm.PackageManager;
import android.os.SystemProperties;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import org.junit.AssumptionViolatedException;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Rule used to properly check a test behavior depending on whether the device supports {@code
 * AdService}.
 *
 * <p>Typical usage:
 *
 * <pre class="prettyprint">
 * &#064;Rule
 * public final AdServicesSupportRule mAdServicesSupportRule = new AdServicesSupportRule();
 * </pre>
 *
 * <p>In the example above, it assumes that every test should only be executed when the device
 * supports {@code AdServices} - if the device doesn't support it, the test will be skipped (with an
 * {@link AssumptionViolatedException}).
 *
 * <p>The rule can also be used to make sure APIs throw {@link UnsupportedOperationException} when
 * the device doesn't support {@code AdServices}; in that case, you annotate the test method with
 * {@link RequiresAdServicesNotSupported}, then simply call the API that should throw the exception
 * on its body - the rule will make sure the exception is thrown (and fail the test if it isn't).
 * Example:
 *
 * <pre class="prettyprint">
 * &#064;Test
 * &#064;RequiresAdServicesNotSupported
 * public void testFoo_notSupported() {
 *    mObjectUnderTest.foo();
 * }
 * </pre>
 */
public final class AdServicesSupportRule extends AbstractSupportedFeatureRule {

    private static final String TAG = AdServicesSupportRule.class.getSimpleName();

    // TODO(b/284971005): add unit test to make sure it's false
    /**
     * When set to {@code true}, it checks whether the device is supported by reading the {@value
     * #DEBUG_PROP_IS_SUPPORTED} system property.
     *
     * <p>Should <b>NEVER</b> be merged as {@code true} - it's only meant to be used locally to
     * develop / debug the rule itself (not real tests).
     */
    private static final boolean ALLOW_OVERRIDE_BY_SYS_PROP = false;

    private static final String DEBUG_PROP_IS_SUPPORTED =
            "debug.AbstractSupportedFeatureRule.supported";

    /** Creates a rule using {@link Mode#NOT_SUPPORTED_BY_DEFAULT}. */
    public AdServicesSupportRule() {
        this(Mode.SUPPORTED_BY_DEFAULT);
    }

    /** Creates a rule with the given mode. */
    public AdServicesSupportRule(Mode mode) {
        super(mode);
    }

    @Override
    boolean isFeatureSupported() {
        boolean isSupported;
        if (ALLOW_OVERRIDE_BY_SYS_PROP) {
            logI("isFeatureSupported(): checking value from property %s", DEBUG_PROP_IS_SUPPORTED);
            isSupported = SystemProperties.getBoolean(DEBUG_PROP_IS_SUPPORTED, true);
        } else {
            isSupported = isDeviceSupported();
        }
        logV("isFeatureSupported(): %b", isSupported);
        return isSupported;
    }

    @Override
    protected void throwFeatureNotSupportedAVE() {
        throw new AssumptionViolatedException("Device doesn't support AdServices");
    }

    @Override
    protected void throwFeatureSupportedAVE() {
        throw new AssumptionViolatedException("Device supports AdServices");
    }

    @Override
    @FormatMethod
    protected void log(LogLevel level, @FormatString String msgFmt, @Nullable Object... msgArgs) {
        String message = String.format(msgFmt, msgArgs);
        switch (level) {
            case ERROR:
                Log.e(TAG, message);
                return;
            case WARNING:
                Log.w(TAG, message);
                return;
            case INFO:
                Log.i(TAG, message);
                return;
            case DEBUG:
                Log.d(TAG, message);
                return;
            case VERBOSE:
                Log.v(TAG, message);
                return;
            default:
                Log.wtf(TAG, "invalid level (" + level + "): " + message);
        }
    }

    @Override
    protected boolean isFeatureSupportedAnnotation(Annotation annotation) {
        return annotation instanceof RequiresAdServicesSupported;
    }

    @Override
    protected boolean isFeatureNotSupportedAnnotation(Annotation annotation) {
        return annotation instanceof RequiresAdServicesNotSupported;
    }

    // TODO(b/284971005): currently it's package-protected and static because it's used by
    // AdservicesTestHelper.isDeviceSupported(). Once that method is gone, inline the logic into
    // isFeatureSupported().
    /** Checks whether AdServices is supported in the device. */
    static boolean isDeviceSupported() {
        // TODO(b/284744130): read from flags
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        PackageManager pm = inst.getContext().getPackageManager();
        return !pm.hasSystemFeature(PackageManager.FEATURE_RAM_LOW) // Android Go Devices
                && !pm.hasSystemFeature(PackageManager.FEATURE_WATCH)
                && !pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                && !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    /**
     * Annotation used to indicate that a test should only be run when the device supports {@code
     * AdServices}.
     *
     * <p>Typically used when the rule was created with {@link Mode#NOT_SUPPORTED_BY_DEFAULT} or or
     * {@link Mode#ANNOTATION_ONLY}.
     */
    @Retention(RUNTIME)
    @Target({TYPE, METHOD})
    public static @interface RequiresAdServicesSupported {}

    /**
     * Annotation used to indicate that a test should only be run when the device does NOT support
     * {@code AdServices}, and that the test should throw a {@link UnsupportedOperationException}.
     *
     * <p>Typically used when the rule was created with {@link Mode#SUPPORTED_BY_DEFAULT} (which is
     * also the rule's default behavior) or or {@link Mode#ANNOTATION_ONLY}.
     */
    @Retention(RUNTIME)
    @Target({TYPE, METHOD})
    public static @interface RequiresAdServicesNotSupported {}
}
