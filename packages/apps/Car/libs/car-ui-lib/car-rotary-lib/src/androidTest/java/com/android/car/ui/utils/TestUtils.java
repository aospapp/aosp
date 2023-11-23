/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.ui.utils;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.ui.FocusParkingView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Utility class for helpful methods used in tests. */
public final class TestUtils {
    private static final long WAIT_TIME_MS = 3000;

    /**
     * Performs the {@code consumer} on the UI thread.
     * <p>
     * The given {@code view} must be attached to window, otherwise the Runnable will never run.
     */
    public static void accept(View view, Consumer<View> consumer)
            throws InterruptedException {
        if (!view.isAttachedToWindow()) {
            throw new RuntimeException("Don't post Runnable on the view because it's detached from"
                    + " window: " + view);
        }
        CountDownLatch latch = new CountDownLatch(1);
        view.post(() -> {
            consumer.accept(view);
            latch.countDown();
        });
        assertWithMessage("Timeout")
                .that(latch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS)).isTrue();
    }

    /**
     * Evaluates the {@code predicate} on the UI thread and returns the result.
     * <p>
     * The given {@code view} must be attached to window, otherwise the Runnable will never run.
     */
    public static boolean test(View view, Predicate<View> predicate)
            throws InterruptedException {
        if (!view.isAttachedToWindow()) {
            throw new RuntimeException("Don't post Runnable on the view because it's detached from"
                    + " window: " + view);
        }
        boolean[] result = new boolean[1];
        CountDownLatch latch = new CountDownLatch(1);
        view.post(() -> {
            result[0] = predicate.test(view);
            latch.countDown();
        });
        assertWithMessage("Timeout")
                .that(latch.await(WAIT_TIME_MS, TimeUnit.MILLISECONDS)).isTrue();
        return result[0];
    }

    /**
     * Hides the focus in the view tree rooted at {@code root} by focusing on the given
     * FocusParkingView {@code fpv} on the UI thread, and asserts it's done successfully.
     */
    public static void hideFocusAndAssertFocusHidden(@NonNull View root,
            @NonNull FocusParkingView fpv) throws InterruptedException {
        accept(root, v -> ViewUtils.hideFocus(v));
        assertThat(fpv.isFocused()).isTrue();
    }

    /**
     * Calls {@link ViewUtils#requestFocus} with {@code view} on the UI thread, and asserts that the
     * method result and {@code view}'s focused state match {@code expectedFocused}.
     * <p>
     * The given {@code view} must be attached to window. Otherwise call
     * {@link #requestFocusAndAssertFocused(View, boolean, View)} instead.
     */
    public static void requestFocusAndAssertFocused(@Nullable View view, boolean expectedFocused)
            throws InterruptedException {
        requestFocusAndAssertFocused(view, expectedFocused, view);
    }

    /**
     * This method is a variant of {@link #requestFocusAndAssertFocused(View, boolean)}. It's
     * used when {@code viewToFocus} is detached from window (e.g., it's removed). In this case,
     * the Runnable is posted on {@code viewToPostRunnable}, which must be attached to window.
     */
    public static void requestFocusAndAssertFocused(@Nullable View viewToFocus,
            boolean expectedFocused, @Nullable View viewToPostRunnable)
            throws InterruptedException {
        boolean result;
        if (viewToFocus == null) {
            result = ViewUtils.requestFocus(viewToFocus);
            assertThat(result).isEqualTo(expectedFocused);
            return;
        }
        result = test(viewToPostRunnable, v -> ViewUtils.requestFocus(viewToFocus));
        assertThat(result).isEqualTo(expectedFocused);
        assertThat(viewToFocus.isFocused()).isEqualTo(expectedFocused);
    }

    /**
     * Calls {@link View#requestFocus} with {@code viewToRequestFocus} on the UI thread, and asserts
     * that {@code viewToGetFocus} ends up being focused.
     */
    public static void requestFocusAndAssertFocused(@NonNull View viewToRequestFocus,
            @NonNull View viewToGetFocus) throws InterruptedException {
        accept(viewToRequestFocus, v -> v.requestFocus());
        assertThat(viewToGetFocus.isFocused()).isTrue();
    }

    /**
     * Adjusts focus in the view tree rooted at {@code root} on the UI thread, and asserts the
     * returned value is {@code expectedSuccess}.
     */
    public static void adjustFocusAndAssertFocusAdjusted(@Nullable View root, int focusLevel,
            boolean expectedSuccess) throws InterruptedException {
        boolean result = test(root, v -> ViewUtils.adjustFocus(root, focusLevel));
        assertThat(result).isEqualTo(expectedSuccess);
    }
}
