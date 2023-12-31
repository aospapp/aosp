/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.cts.mockime;

import android.inputmethodservice.AbstractInputMethodService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.view.View;
import android.view.inputmethod.TextSnapshot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable object that stores event happened in the {@link MockIme}.
 */
public final class ImeEvent {

    private enum ReturnType {
        Null,
        Unavailable,
        KnownUnsupportedType,
        Boolean,
        Integer,
        Long,
        String,
        CharSequence,
        Exception,
        Parcelable,
        List
    }

    /**
     * A special placeholder object that represents that return value information is not available.
     */
    static final Object RETURN_VALUE_UNAVAILABLE = new Object();

    private static ReturnType getReturnTypeFromObject(@Nullable Object object) {
        if (object == null) {
            return ReturnType.Null;
        }
        if (object == RETURN_VALUE_UNAVAILABLE) {
            return ReturnType.Unavailable;
        }
        if (object instanceof AbstractInputMethodService.AbstractInputMethodImpl) {
            return ReturnType.KnownUnsupportedType;
        }
        if (object instanceof View) {
            return ReturnType.KnownUnsupportedType;
        }
        if (object instanceof Handler) {
            return ReturnType.KnownUnsupportedType;
        }
        if (object instanceof TextSnapshot) {
            return ReturnType.KnownUnsupportedType;
        }
        if (object instanceof Boolean) {
            return ReturnType.Boolean;
        }
        if (object instanceof Integer) {
            return ReturnType.Integer;
        }
        if (object instanceof Long) {
            return ReturnType.Long;
        }
        if (object instanceof String) {
            return ReturnType.String;
        }
        if (object instanceof CharSequence) {
            return ReturnType.CharSequence;
        }
        if (object instanceof Exception) {
            return ReturnType.Exception;
        }
        if (object instanceof Parcelable) {
            return ReturnType.Parcelable;
        }
        if (object instanceof List) {
            return ReturnType.List;
        }
        throw new UnsupportedOperationException("Unsupported return type=" + object);
    }

    ImeEvent(@NonNull String eventName, int nestLevel, @NonNull String threadName, int threadId,
            boolean isMainThread, long enterTimestamp, long exitTimestamp, long enterWallTime,
            long exitWallTime, @NonNull ImeState enterState, @Nullable ImeState exitState,
            @NonNull Bundle arguments, @Nullable Object returnValue) {
        this(eventName, nestLevel, threadName, threadId, isMainThread, enterTimestamp,
                exitTimestamp, enterWallTime, exitWallTime, enterState, exitState, arguments,
                returnValue, getReturnTypeFromObject(returnValue));
    }

    private ImeEvent(@NonNull String eventName, int nestLevel, @NonNull String threadName,
            int threadId, boolean isMainThread, long enterTimestamp, long exitTimestamp,
            long enterWallTime, long exitWallTime, @NonNull ImeState enterState,
            @Nullable ImeState exitState, @NonNull Bundle arguments, @Nullable Object returnValue,
            @NonNull ReturnType returnType) {
        mEventName = eventName;
        mNestLevel = nestLevel;
        mThreadName = threadName;
        mThreadId = threadId;
        mIsMainThread = isMainThread;
        mEnterTimestamp = enterTimestamp;
        mExitTimestamp = exitTimestamp;
        mEnterWallTime = enterWallTime;
        mExitWallTime = exitWallTime;
        mEnterState = enterState;
        mExitState = exitState;
        mArguments = arguments;
        mReturnValue = returnValue;
        mReturnType = returnType;
    }

    @NonNull
    Bundle toBundle() {
        final Bundle bundle = new Bundle();
        bundle.putString("mEventName", mEventName);
        bundle.putInt("mNestLevel", mNestLevel);
        bundle.putString("mThreadName", mThreadName);
        bundle.putInt("mThreadId", mThreadId);
        bundle.putBoolean("mIsMainThread", mIsMainThread);
        bundle.putLong("mEnterTimestamp", mEnterTimestamp);
        bundle.putLong("mExitTimestamp", mExitTimestamp);
        bundle.putLong("mEnterWallTime", mEnterWallTime);
        bundle.putLong("mExitWallTime", mExitWallTime);
        bundle.putBundle("mEnterState", mEnterState.toBundle());
        bundle.putBundle("mExitState", mExitState != null ? mExitState.toBundle() : null);
        bundle.putBundle("mArguments", mArguments);
        bundle.putString("mReturnType", mReturnType.name());
        switch (mReturnType) {
            case Null:
            case Unavailable:
            case KnownUnsupportedType:
                break;
            case Boolean:
                bundle.putBoolean("mReturnValue", getReturnBooleanValue());
                break;
            case Integer:
                bundle.putInt("mReturnValue", getReturnIntegerValue());
                break;
            case Long:
                bundle.putLong("mReturnValue", getReturnLongValue());
                break;
            case String:
                bundle.putString("mReturnValue", getReturnStringValue());
                break;
            case CharSequence:
                bundle.putCharSequence("mReturnValue", getReturnCharSequenceValue());
                break;
            case Exception:
                bundle.putSerializable("mReturnValue", getReturnExceptionValue());
                break;
            case Parcelable:
                bundle.putParcelable("mReturnValue", getReturnParcelableValue());
                break;
            case List:
                bundle.putParcelableArrayList("mReturnValue", getReturnParcelableArrayListValue());
                break;
            default:
                throw new UnsupportedOperationException("Unsupported type=" + mReturnType);
        }
        return bundle;
    }

    @NonNull
    static ImeEvent fromBundle(@NonNull Bundle bundle) {
        final String eventName = bundle.getString("mEventName");
        final int nestLevel = bundle.getInt("mNestLevel");
        final String threadName = bundle.getString("mThreadName");
        final int threadId = bundle.getInt("mThreadId");
        final boolean isMainThread = bundle.getBoolean("mIsMainThread");
        final long enterTimestamp = bundle.getLong("mEnterTimestamp");
        final long exitTimestamp = bundle.getLong("mExitTimestamp");
        final long enterWallTime = bundle.getLong("mEnterWallTime");
        final long exitWallTime = bundle.getLong("mExitWallTime");
        final ImeState enterState = ImeState.fromBundle(bundle.getBundle("mEnterState"));
        final ImeState exitState = ImeState.fromBundle(bundle.getBundle("mExitState"));
        final Bundle arguments = bundle.getBundle("mArguments");
        final Object result;
        final ReturnType returnType = ReturnType.valueOf(bundle.getString("mReturnType"));
        switch (returnType) {
            case Null:
            case Unavailable:
            case KnownUnsupportedType:
                result = null;
                break;
            case Boolean:
                result = bundle.getBoolean("mReturnValue");
                break;
            case Integer:
                result = bundle.getInt("mReturnValue");
                break;
            case Long:
                result = bundle.getLong("mReturnValue");
                break;
            case String:
                result = bundle.getString("mReturnValue");
                break;
            case CharSequence:
                result = bundle.getCharSequence("mReturnValue");
                break;
            case Exception:
                result = bundle.getSerializable("mReturnValue");
                break;
            case Parcelable:
                result = bundle.getParcelable("mReturnValue");
                break;
            case List:
                result = bundle.getParcelableArrayList("mReturnValue");
                break;
            default:
                throw new UnsupportedOperationException("Unsupported type=" + returnType);
        }
        return new ImeEvent(eventName, nestLevel, threadName,
                threadId, isMainThread, enterTimestamp, exitTimestamp, enterWallTime, exitWallTime,
                enterState, exitState, arguments, result, returnType);
    }

    /**
     * Returns a string that represents the type of this event.
     *
     * <p>Examples: &quot;onCreate&quot;, &quot;onStartInput&quot;, ...</p>
     *
     * <p>TODO: Use enum type or something like that instead of raw String type.</p>
     * @return A string that represents the type of this event.
     */
    @NonNull
    public String getEventName() {
        return mEventName;
    }

    /**
     * Returns the nest level of this event.
     *
     * <p>For instance, when &quot;showSoftInput&quot; internally calls
     * &quot;onStartInputView&quot;, the event for &quot;onStartInputView&quot; has 1 level higher
     * nest level than &quot;showSoftInput&quot;.</p>
     */
    public int getNestLevel() {
        return mNestLevel;
    }

    /**
     * @return Name of the thread, where the event was consumed.
     */
    @NonNull
    public String getThreadName() {
        return mThreadName;
    }

    /**
     * @return Thread ID (TID) of the thread, where the event was consumed.
     */
    public int getThreadId() {
        return mThreadId;
    }

    /**
     * @return {@code true} if the event was being consumed in the main thread.
     */
    public boolean isMainThread() {
        return mIsMainThread;
    }

    /**
     * @return Monotonic time measured by {@link android.os.SystemClock#elapsedRealtimeNanos()} when
     *         the corresponding event handler was called back.
     */
    public long getEnterTimestamp() {
        return mEnterTimestamp;
    }

    /**
     * @return Monotonic time measured by {@link android.os.SystemClock#elapsedRealtimeNanos()} when
     *         the corresponding event handler finished.
     */
    public long getExitTimestamp() {
        return mExitTimestamp;
    }

    /**
     * @return Wall-clock time measured by {@link System#currentTimeMillis()} when the corresponding
     *         event handler was called back.
     */
    public long getEnterWallTime() {
        return mEnterWallTime;
    }

    /**
     * @return Wall-clock time measured by {@link System#currentTimeMillis()} when the corresponding
     *         event handler finished.
     */
    public long getExitWallTime() {
        return mExitWallTime;
    }

    /**
     * @return IME state snapshot taken when the corresponding event handler was called back.
     */
    @NonNull
    public ImeState getEnterState() {
        return mEnterState;
    }

    /**
     * @return IME state snapshot taken when the corresponding event handler finished.
     */
    @Nullable
    public ImeState getExitState() {
        return mExitState;
    }

    /**
     * @return {@link Bundle} that stores parameters passed to the corresponding event handler.
     */
    @NonNull
    public Bundle getArguments() {
        return mArguments;
    }

    /**
     * @return result value of this event.
     * @throws NullPointerException if the return value is {@code null}
     * @throws ClassCastException if the return value is non-{@code null} object that is different
     *                            from {@link Boolean}
     */
    public boolean getReturnBooleanValue() {
        if (mReturnType == ReturnType.Null) {
            throw new NullPointerException();
        }
        if (mReturnType != ReturnType.Boolean) {
            throw new ClassCastException();
        }
        return (Boolean) mReturnValue;
    }

    /**
     * @return result value of this event.
     * @throws NullPointerException if the return value is {@code null}
     * @throws ClassCastException if the return value is non-{@code null} object that is different
     *                            from {@link Integer}
     */
    public int getReturnIntegerValue() {
        if (mReturnType == ReturnType.Null) {
            throw new NullPointerException();
        }
        if (mReturnType != ReturnType.Integer) {
            throw new ClassCastException();
        }
        return (Integer) mReturnValue;
    }

    /**
     * @return result value of this event.
     * @throws NullPointerException if the return value is {@code null}
     * @throws ClassCastException if the return value is non-{@code null} object that is different
     *                            from {@link Long}
     */
    public long getReturnLongValue() {
        if (mReturnType == ReturnType.Null) {
            throw new NullPointerException();
        }
        if (mReturnType != ReturnType.Long) {
            throw new ClassCastException();
        }
        return (Long) mReturnValue;
    }

    /**
     * @return result value of this event.
     * @throws NullPointerException if the return value is {@code null}
     * @throws ClassCastException if the return value is non-{@code null} object that does not
     *                            implement {@link CharSequence}
     */
    public CharSequence getReturnCharSequenceValue() {
        if (mReturnType == ReturnType.Null) {
            throw new NullPointerException();
        }
        if (mReturnType == ReturnType.CharSequence || mReturnType == ReturnType.String
                || mReturnType == ReturnType.Parcelable) {
            return (CharSequence) mReturnValue;
        }
        throw new ClassCastException();
    }

    /**
     * @return result value of this event.
     * @throws NullPointerException if the return value is {@code null}
     * @throws ClassCastException if the return value is non-{@code null} object that is different
     *                            from {@link String}
     */
    public String getReturnStringValue() {
        if (mReturnType == ReturnType.Null) {
            throw new NullPointerException();
        }
        if (mReturnType != ReturnType.String) {
            throw new ClassCastException();
        }
        return (String) mReturnValue;
    }

     /**
      * Retrieves a result that is known to be {@link Exception} or its subclasses.
      *
      * @param <T> {@link Exception} or its subclass.
      * @return {@link Exception} object returned as a result of the command.
      * @throws NullPointerException if the return value is {@code null}
      * @throws ClassCastException if the return value is non-{@code null} object that is different
      *                            from {@link Exception}
     */
    public <T extends Exception> T getReturnExceptionValue() {
        if (mReturnType == ReturnType.Null) {
            throw new NullPointerException();
        }
        if (mReturnType != ReturnType.Exception) {
            throw new ClassCastException();
        }
        return (T) mReturnValue;
    }

    /**
     * @return result value of this event.
     * @throws NullPointerException if the return value is {@code null}
     * @throws ClassCastException if the return value is non-{@code null} object that is different
     *                            from {@link Parcelable}
     */
    public <T extends Parcelable> T getReturnParcelableValue() {
        if (mReturnType == ReturnType.Null) {
            throw new NullPointerException();
        }
        if (mReturnType != ReturnType.Parcelable) {
            throw new ClassCastException();
        }
        return (T) mReturnValue;
    }

    /**
     * @return result value of this event.
     * @throws NullPointerException if the return value is {@code null}
     * @throws ClassCastException if the return value is non-{@code null} object that is different
     *                            from {@link ArrayList<? extends Parcelable>}
     */
    public <T extends Parcelable> ArrayList<T> getReturnParcelableArrayListValue() {
        if (mReturnType == ReturnType.Null) {
            throw new NullPointerException();
        }
        if (mReturnType != ReturnType.List) {
            throw new ClassCastException();
        }
        return (ArrayList<T>) mReturnValue;
    }

    /**
     * @return {@code true} when the result value is an {@link Exception}.
     */
    public boolean isExceptionReturnValue() {
        return mReturnType == ReturnType.Exception;
    }

    /**
     * @return {@code true} when the result value is {@code null}.
     */
    public boolean isNullReturnValue() {
        return mReturnType == ReturnType.Null;
    }

    /**
     * @return {@code true} if the event is issued when the event starts, not when the event
     * finishes.
     */
    public boolean isEnterEvent() {
        return mExitState == null;
    }

    @NonNull
    private final String mEventName;
    private final int mNestLevel;
    @NonNull
    private final String mThreadName;
    private final int mThreadId;
    private final boolean mIsMainThread;
    private final long mEnterTimestamp;
    private final long mExitTimestamp;
    private final long mEnterWallTime;
    private final long mExitWallTime;
    @NonNull
    private final ImeState mEnterState;
    @Nullable
    private final ImeState mExitState;
    @NonNull
    private final Bundle mArguments;
    @Nullable
    private final Object mReturnValue;
    @NonNull
    private final ReturnType mReturnType;
}
