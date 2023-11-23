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

package com.android.car.apps.common.util;

import static java.util.Objects.requireNonNull;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;
import androidx.core.util.Pair;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Utility methods for using {@link LiveData}. In general for Boolean operations, {@code null} is
 * treated as an "unknown" value, and operations may use short-circuit evaluation to determine the
 * result. LiveData may be in an uninitialized state where observers are not called when registered
 * (e.g. a {@link MutableLiveData} where {@link MutableLiveData#setValue(Object)} has not yet been
 * called). If a Boolean operation receives an uninitialized LiveData as either of its parameters,
 * the result will also be in an uninitialized state.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class LiveDataFunctions {

    private LiveDataFunctions() {
    }

    private static volatile LiveData<?> sNullLiveData;
    private static volatile LiveData<Boolean> sTrueLiveData;
    private static volatile LiveData<Boolean> sFalseLiveData;

    /**
     * Returns a LiveData that always emits {@code null}. This is different than an uninitialized
     * LiveData in that observers will be called (with {@code null}) when registered.
     */
    public static <T> LiveData<T> nullLiveData() {
        if (sNullLiveData == null) {
            sNullLiveData = dataOf(null);
        }
        // null can fit any generic type
        // noinspection unchecked
        return (LiveData<T>) sNullLiveData;
    }

    /** Returns a LiveData that is initialized with {@code value}. */
    public static <T> MutableLiveData<T> dataOf(@Nullable T value) {
        MutableLiveData<T> data = new MutableLiveData<>();
        data.setValue(value);
        return data;
    }

    /**
     * Similar to {@link Transformations#map(LiveData, Function)}, but emits {@code null} when
     * {@code source} emits {@code null}. The input to {@code func} may be treated as not nullable.
     */
    public static <T, R> LiveData<R> mapNonNull(@NonNull LiveData<T> source,
            @NonNull Function<T, R> func) {
        return mapNonNull(source, null, func);
    }

    /**
     * Similar to {@link Transformations#map(LiveData, Function)}, but emits {@code nullValue} when
     * {@code source} emits {@code null}. The input to {@code func} may be treated as not nullable.
     */
    public static <T, R> LiveData<R> mapNonNull(@NonNull LiveData<T> source, @Nullable R nullValue,
            @NonNull Function<T, R> func) {
        return Transformations.map(source, value -> value == null ? nullValue : func.apply(value));
    }

    /**
     * Similar to {@link Transformations#switchMap(LiveData, Function)}, but emits {@code null} when
     * {@code source} emits {@code null}. The input to {@code func} may be treated as not nullable.
     */
    public static <T, R> LiveData<R> switchMapNonNull(@NonNull LiveData<T> source,
            @NonNull Function<T, LiveData<R>> func) {
        return switchMapNonNull(source, null, func);
    }

    /**
     * Similar to {@link Transformations#switchMap(LiveData, Function)}, but emits {@code nullValue}
     * when {@code source} emits {@code null}. The input to {@code func} may be treated as not
     * nullable.
     */
    public static <T, R> LiveData<R> switchMapNonNull(@NonNull LiveData<T> source,
            @Nullable R nullValue,
            @NonNull Function<T, LiveData<R>> func) {
        return Transformations.switchMap(source,
                value -> value == null ? nullLiveData() : func.apply(value));
    }

    /**
     * Similar to {@link Transformations#switchMap(LiveData, Function)}, but emits a FutureData,
     * which provides a loading field for operations which may take a long time to finish.
     *
     * This LiveData emits values only when the loading status of the output changes. It will never
     * emit {@code null}. If the output is loading, the emitted FutureData will have a null value
     * for the data.
     */
    public static <T, R> LiveData<FutureData<R>> loadingSwitchMap(LiveData<T> trigger,
            @NonNull Function<T, LiveData<R>> func) {
        LiveData<R> output = Transformations.switchMap(trigger, func);
        return new MediatorLiveData<FutureData<R>>() {
            {
                addSource(trigger, data -> setValue(new FutureData<>(true, null)));
                addSource(output, data ->
                        setValue(new FutureData<>(false, output.getValue())));
            }
        };
    }

    /**
     * Returns a LiveData backed by {@code value} if and only if predicate emits {@code true}. Emits
     * {@code null} otherwise.
     * <p>
     * This is equivalent to {@code iff(predicate, Boolean::booleanValue, value)}
     *
     * @see #iff(LiveData, Predicate, LiveData)
     */
    public static <T> LiveData<T> iff(
            @NonNull LiveData<Boolean> predicate, @NonNull LiveData<T> value) {
        return iff(predicate, Boolean::booleanValue, value);
    }

    /**
     * Returns a LiveData backed by {@code value} if and only if the trigger emits a value that
     * causes {@code predicate} to return {@code true}. Emits {@code null} otherwise.
     */
    public static <P, T> LiveData<T> iff(
            @NonNull LiveData<P> trigger,
            @NonNull Predicate<? super P> predicate,
            @NonNull LiveData<T> value) {
        return new BinaryOperation<>(
                trigger, value, (p, v) -> p == null || !predicate.test(p) ? null : v);
    }

    /**
     * Returns a LiveData that emits a Pair containing the values of the two parameter LiveDatas. If
     * either parameter is uninitialized, the resulting LiveData is also uninitialized.
     * <p>
     * This is equivalent to calling {@code combine(tData, uData, Pair::new)}.
     *
     * @see #combine(LiveData, LiveData, BiFunction)
     */
    public static <T, U> LiveData<Pair<T, U>> pair(
            @NonNull LiveData<T> tData, @NonNull LiveData<U> uData) {
        return combine(tData, uData, Pair::new);
    }

    /**
     * Returns a LiveData that emits the result of {@code function} on the values of the two
     * parameter LiveDatas. If either parameter is uninitialized, the resulting LiveData is also
     * uninitialized.
     */
    public static <T, U, R> LiveData<R> combine(
            @NonNull LiveData<T> tData,
            @NonNull LiveData<U> uData,
            @NonNull BiFunction<T, U, R> function) {
        return new BinaryOperation<>(tData, uData, function);
    }

    private static class BinaryOperation<T, U, R> extends MediatorLiveData<R> {
        @NonNull
        private final BiFunction<T, U, R> mFunction;

        private boolean mTSet;
        private boolean mUSet;
        private boolean mValueSet;

        @Nullable
        private T mTValue;
        @Nullable
        private U mUValue;

        BinaryOperation(
                @NonNull LiveData<T> tLiveData,
                @NonNull LiveData<U> uLiveData,
                @NonNull BiFunction<T, U, R> function) {
            this(tLiveData, uLiveData, true, true, function);
        }

        BinaryOperation(
                @NonNull LiveData<T> tLiveData,
                @NonNull LiveData<U> uLiveData,
                boolean requireTSet,
                boolean requireUSet,
                @NonNull BiFunction<T, U, R> function) {
            this.mFunction = function;
            if (!requireTSet) {
                mTSet = true;
            }
            if (!requireUSet) {
                mUSet = true;
            }
            if (tLiveData == uLiveData) {
                // Only add the source once and only update once when it changes.
                addSource(
                        tLiveData,
                        value -> {
                            mTSet = true;
                            mUSet = true;
                            mTValue = value;
                            // if both references point to the same LiveData, then T and U are
                            // compatible types.
                            // noinspection unchecked
                            mUValue = (U) value;
                            update();
                        });
            } else {
                addSource(requireNonNull(tLiveData), this::updateT);
                addSource(requireNonNull(uLiveData), this::updateU);
            }
        }

        private void updateT(@Nullable T tValue) {
            mTSet = true;
            this.mTValue = tValue;
            update();
        }

        private void updateU(@Nullable U uValue) {
            mUSet = true;
            this.mUValue = uValue;
            update();
        }

        private void update() {
            if (mTSet && mUSet) {
                R result = mFunction.apply(mTValue, mUValue);
                // Don't setValue if it's the same as the old value unless we haven't set a value
                // before.
                if (!mValueSet || result != getValue()) {
                    mValueSet = true;
                    setValue(result);
                }
            }
        }
    }
}
