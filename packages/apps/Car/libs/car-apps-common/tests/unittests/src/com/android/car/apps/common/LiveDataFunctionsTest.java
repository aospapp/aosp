/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.car.apps.common;

import static com.android.car.apps.common.util.LiveDataFunctions.dataOf;
import static com.android.car.apps.common.util.LiveDataFunctions.nullLiveData;

import static com.google.common.truth.Truth.assertThat;

import androidx.core.util.Pair;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.apps.common.testutils.CaptureObserver;
import com.android.car.apps.common.testutils.InstantTaskExecutorRule;
import com.android.car.apps.common.testutils.TestLifecycleOwner;
import com.android.car.apps.common.util.LiveDataFunctions;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
public class LiveDataFunctionsTest {

    @Rule
    public final InstantTaskExecutorRule mRule = new InstantTaskExecutorRule();
    @Rule
    public final TestLifecycleOwner mLifecycleOwner = new TestLifecycleOwner();

    @Test
    public void testNullLiveData() {
        CaptureObserver<Object> observer = new CaptureObserver<>();
        nullLiveData().observe(mLifecycleOwner, observer);

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isNull();
        assertThat(nullLiveData().getValue()).isNull();
    }

    @Test
    public void testIff_truthTable() {
        Object object = new Object();
        testBinaryOperator(
                (predicate, value) -> LiveDataFunctions.iff(predicate, Boolean::booleanValue,
                        value),
                pair(pair(dataOf(true), dataOf(object)), object),
                pair(pair(dataOf(false), dataOf(object)), null),
                pair(pair(nullLiveData(), dataOf(object)), null));
    }

    @Test
    public void testIff_uninitialized() {
        checkUninitializedBinary(
                (predicate, value) -> LiveDataFunctions.iff(predicate, Boolean::booleanValue,
                        value),
                pair(new MutableLiveData<>(), dataOf(new Object())),
                pair(dataOf(false), new MutableLiveData<>()),
                pair(dataOf(true), new MutableLiveData<>()));
    }

    @Test
    public void testIff_changePredicate() {
        MutableLiveData<Boolean> predicate = new MutableLiveData<>();
        MutableLiveData<Object> value = new MutableLiveData<>();
        Object valueObject = new Object();
        value.setValue(valueObject);
        CaptureObserver<Object> observer = new CaptureObserver<>();

        LiveDataFunctions.iff(predicate, Boolean::booleanValue, value)
                .observe(mLifecycleOwner, observer);
        assertThat(observer.hasBeenNotified()).isFalse();

        predicate.setValue(false);

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isNull();
        observer.reset();

        predicate.setValue(true);

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isSameInstanceAs(valueObject);
        observer.reset();

        predicate.setValue(null);

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isNull();
    }

    @Test
    public void testIff_changeValue() {
        LiveData<Boolean> predicate = dataOf(true);
        MutableLiveData<Object> value = new MutableLiveData<>();
        Object firstObject = new Object();
        CaptureObserver<Object> observer = new CaptureObserver<>();

        LiveDataFunctions.iff(predicate, Boolean::booleanValue, value)
                .observe(mLifecycleOwner, observer);
        assertThat(observer.hasBeenNotified()).isFalse();

        value.setValue(null);

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isNull();
        observer.reset();

        value.setValue(firstObject);

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isSameInstanceAs(firstObject);
        observer.reset();

        value.setValue(new Object());

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isNotSameInstanceAs(firstObject);
    }

    @Test
    public void testIff_changeValue_doesntNotifyForIrrelevantChanges() {
        MutableLiveData<Object> irrelevantValue = new MutableLiveData<>();
        irrelevantValue.setValue(new Object());
        CaptureObserver<Object> observer = new CaptureObserver<>();

        // irrelevantValue irrelevant because iff() always emits null when predicate is false.
        LiveDataFunctions.iff(dataOf(false), Boolean::booleanValue, irrelevantValue)
                .observe(mLifecycleOwner, observer);
        assertThat(observer.hasBeenNotified()).isTrue();
        observer.reset();

        irrelevantValue.setValue(null);

        assertThat(observer.hasBeenNotified()).isFalse();
    }

    @Test
    public void testCombine() {
        Object first = new Object();
        Object second = new Object();

        MutableLiveData<Object> firstData = new MutableLiveData<>();
        MutableLiveData<Object> secondData = new MutableLiveData<>();
        firstData.setValue(first);
        secondData.setValue(second);

        CaptureObserver<Pair<Object, Object>> observer = new CaptureObserver<>();
        LiveDataFunctions.combine(firstData, secondData, Pair::new).observe(mLifecycleOwner,
                observer);

        Pair<Object, Object> observedValue = observer.getObservedValue();
        assertThat(observedValue).isNotNull();
        assertThat(observedValue.first).isSameInstanceAs(first);
        assertThat(observedValue.second).isSameInstanceAs(second);

        Object third = new Object();
        firstData.setValue(third);

        observedValue = observer.getObservedValue();
        assertThat(observedValue).isNotNull();
        assertThat(observedValue.first).isSameInstanceAs(third);
        assertThat(observedValue.second).isSameInstanceAs(second);
    }

    private <R> void testOperator(Supplier<LiveData<R>> op, R result) {
        CaptureObserver<R> observer = new CaptureObserver<>();
        LiveData<R> data = op.get();

        data.observe(mLifecycleOwner, observer);

        assertThat(observer.hasBeenNotified()).isTrue();
        assertThat(observer.getObservedValue()).isEqualTo(result);
        assertThat(data.getValue()).isEqualTo(result);
    }

    @SafeVarargs // args are never written to
    private final <P, R> void testOperator(
            Function<P, Supplier<LiveData<R>>> ops, Pair<P, R>... args) {
        for (Pair<P, R> arg : args) {
            testOperator(ops.apply(arg.first), arg.second);
        }
    }

    @SafeVarargs // args are never written to
    private final <T, R> void testUnaryOperator(
            Function<LiveData<T>, LiveData<R>> op, Pair<LiveData<T>, R>... args) {
        testOperator(arg -> () -> op.apply(arg), args);
    }

    @SafeVarargs // args are never written to
    private final <A, B, R> void testBinaryOperator(
            BiFunction<LiveData<A>, LiveData<B>, LiveData<R>> op,
            Pair<Pair<LiveData<A>, LiveData<B>>, R>... args) {
        testOperator(arg -> () -> op.apply(arg.first, arg.second), args);
    }

    private <T, R> Pair<T, R> pair(T first, R second) {
        return new Pair<>(first, second);
    }

    private <T> void checkUninitialized(LiveData<T> liveData) {
        CaptureObserver<T> observer = new CaptureObserver<>();

        liveData.observe(mLifecycleOwner, observer);

        assertThat(observer.hasBeenNotified()).isFalse();
        assertThat(liveData.getValue()).isNull();
    }

    @SafeVarargs // args are never written to
    private final <A, B> void checkUninitializedBinary(
            BiFunction<LiveData<A>, LiveData<B>, LiveData<?>> op,
            Pair<LiveData<A>, LiveData<B>>... args) {
        for (Pair<LiveData<A>, LiveData<B>> arg : args) {
            checkUninitialized(op.apply(arg.first, arg.second));
        }
    }
}
