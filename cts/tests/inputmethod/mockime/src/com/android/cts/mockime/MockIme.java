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

import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethod;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.AnyThread;
import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Mock IME for end-to-end tests.
 */
public final class MockIme extends InputMethodService {

    private static final String TAG = "MockIme";

    private static final String PACKAGE_NAME = "com.android.cts.mockime";

    static ComponentName getComponentName() {
        return new ComponentName(PACKAGE_NAME, MockIme.class.getName());
    }

    static String getImeId() {
        return getComponentName().flattenToShortString();
    }

    static String getCommandActionName(@NonNull String eventActionName) {
        return eventActionName + ".command";
    }

    private final HandlerThread mHandlerThread = new HandlerThread("CommandReceiver");

    private final Handler mMainHandler = new Handler();

    private static final class CommandReceiver extends BroadcastReceiver {
        @NonNull
        private final String mActionName;
        @NonNull
        private final Consumer<ImeCommand> mOnReceiveCommand;

        CommandReceiver(@NonNull String actionName,
                @NonNull Consumer<ImeCommand> onReceiveCommand) {
            mActionName = actionName;
            mOnReceiveCommand = onReceiveCommand;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (TextUtils.equals(mActionName, intent.getAction())) {
                mOnReceiveCommand.accept(ImeCommand.fromBundle(intent.getExtras()));
            }
        }
    }

    @WorkerThread
    private void onReceiveCommand(@NonNull ImeCommand command) {
        getTracer().onReceiveCommand(command, () -> {
            if (command.shouldDispatchToMainThread()) {
                mMainHandler.post(() -> onHandleCommand(command));
            } else {
                onHandleCommand(command);
            }
        });
    }

    @AnyThread
    private void onHandleCommand(@NonNull ImeCommand command) {
        getTracer().onHandleCommand(command, () -> {
            if (command.shouldDispatchToMainThread()) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    throw new IllegalStateException("command " + command
                            + " should be handled on the main thread");
                }
                switch (command.getName()) {
                    case "getTextBeforeCursor": {
                        final int n = command.getExtras().getInt("n");
                        final int flag = command.getExtras().getInt("flag");
                        return getCurrentInputConnection().getTextBeforeCursor(n, flag);
                    }
                    case "getTextAfterCursor": {
                        final int n = command.getExtras().getInt("n");
                        final int flag = command.getExtras().getInt("flag");
                        return getCurrentInputConnection().getTextAfterCursor(n, flag);
                    }
                    case "getSelectedText": {
                        final int flag = command.getExtras().getInt("flag");
                        return getCurrentInputConnection().getSelectedText(flag);
                    }
                    case "getCursorCapsMode": {
                        final int reqModes = command.getExtras().getInt("reqModes");
                        return getCurrentInputConnection().getCursorCapsMode(reqModes);
                    }
                    case "getExtractedText": {
                        final ExtractedTextRequest request =
                                command.getExtras().getParcelable("request");
                        final int flags = command.getExtras().getInt("flags");
                        return getCurrentInputConnection().getExtractedText(request, flags);
                    }
                    case "deleteSurroundingText": {
                        final int beforeLength = command.getExtras().getInt("beforeLength");
                        final int afterLength = command.getExtras().getInt("afterLength");
                        return getCurrentInputConnection().deleteSurroundingText(
                                beforeLength, afterLength);
                    }
                    case "deleteSurroundingTextInCodePoints": {
                        final int beforeLength = command.getExtras().getInt("beforeLength");
                        final int afterLength = command.getExtras().getInt("afterLength");
                        return getCurrentInputConnection().deleteSurroundingTextInCodePoints(
                                beforeLength, afterLength);
                    }
                    case "setComposingText": {
                        final CharSequence text = command.getExtras().getCharSequence("text");
                        final int newCursorPosition =
                                command.getExtras().getInt("newCursorPosition");
                        return getCurrentInputConnection().setComposingText(
                                text, newCursorPosition);
                    }
                    case "setComposingRegion": {
                        final int start = command.getExtras().getInt("start");
                        final int end = command.getExtras().getInt("end");
                        return getCurrentInputConnection().setComposingRegion(start, end);
                    }
                    case "finishComposingText":
                        return getCurrentInputConnection().finishComposingText();
                    case "commitText": {
                        final CharSequence text = command.getExtras().getCharSequence("text");
                        final int newCursorPosition =
                                command.getExtras().getInt("newCursorPosition");
                        return getCurrentInputConnection().commitText(text, newCursorPosition);
                    }
                    case "commitCompletion": {
                        final CompletionInfo text = command.getExtras().getParcelable("text");
                        return getCurrentInputConnection().commitCompletion(text);
                    }
                    case "commitCorrection": {
                        final CorrectionInfo correctionInfo =
                                command.getExtras().getParcelable("correctionInfo");
                        return getCurrentInputConnection().commitCorrection(correctionInfo);
                    }
                    case "setSelection": {
                        final int start = command.getExtras().getInt("start");
                        final int end = command.getExtras().getInt("end");
                        return getCurrentInputConnection().setSelection(start, end);
                    }
                    case "performEditorAction": {
                        final int editorAction = command.getExtras().getInt("editorAction");
                        return getCurrentInputConnection().performEditorAction(editorAction);
                    }
                    case "performContextMenuAction": {
                        final int id = command.getExtras().getInt("id");
                        return getCurrentInputConnection().performContextMenuAction(id);
                    }
                    case "beginBatchEdit":
                        return getCurrentInputConnection().beginBatchEdit();
                    case "endBatchEdit":
                        return getCurrentInputConnection().endBatchEdit();
                    case "sendKeyEvent": {
                        final KeyEvent event = command.getExtras().getParcelable("event");
                        return getCurrentInputConnection().sendKeyEvent(event);
                    }
                    case "clearMetaKeyStates": {
                        final int states = command.getExtras().getInt("states");
                        return getCurrentInputConnection().clearMetaKeyStates(states);
                    }
                    case "reportFullscreenMode": {
                        final boolean enabled = command.getExtras().getBoolean("enabled");
                        return getCurrentInputConnection().reportFullscreenMode(enabled);
                    }
                    case "performPrivateCommand": {
                        final String action = command.getExtras().getString("action");
                        final Bundle data = command.getExtras().getBundle("data");
                        return getCurrentInputConnection().performPrivateCommand(action, data);
                    }
                    case "requestCursorUpdates": {
                        final int cursorUpdateMode = command.getExtras().getInt("cursorUpdateMode");
                        return getCurrentInputConnection().requestCursorUpdates(cursorUpdateMode);
                    }
                    case "getHandler":
                        return getCurrentInputConnection().getHandler();
                    case "closeConnection":
                        getCurrentInputConnection().closeConnection();
                        return ImeEvent.RETURN_VALUE_UNAVAILABLE;
                    case "commitContent": {
                        final InputContentInfo inputContentInfo =
                                command.getExtras().getParcelable("inputContentInfo");
                        final int flags = command.getExtras().getInt("flags");
                        final Bundle opts = command.getExtras().getBundle("opts");
                        return getCurrentInputConnection().commitContent(
                                inputContentInfo, flags, opts);
                    }
                    case "setBackDisposition": {
                        final int backDisposition =
                                command.getExtras().getInt("backDisposition");
                        setBackDisposition(backDisposition);
                        return ImeEvent.RETURN_VALUE_UNAVAILABLE;
                    }
                    case "requestHideSelf": {
                        final int flags = command.getExtras().getInt("flags");
                        requestHideSelf(flags);
                        return ImeEvent.RETURN_VALUE_UNAVAILABLE;
                    }
                    case "requestShowSelf": {
                        final int flags = command.getExtras().getInt("flags");
                        requestShowSelf(flags);
                        return ImeEvent.RETURN_VALUE_UNAVAILABLE;
                    }
                    case "sendDownUpKeyEvents": {
                        final int keyEventCode = command.getExtras().getInt("keyEventCode");
                        sendDownUpKeyEvents(keyEventCode);
                        return ImeEvent.RETURN_VALUE_UNAVAILABLE;
                    }
                    case "getDisplayId":
                        return getSystemService(WindowManager.class)
                                .getDefaultDisplay().getDisplayId();
                }
            }
            return ImeEvent.RETURN_VALUE_UNAVAILABLE;
        });
    }

    @Nullable
    private CommandReceiver mCommandReceiver;

    @Nullable
    private ImeSettings mSettings;

    private final AtomicReference<String> mImeEventActionName = new AtomicReference<>();

    @Nullable
    String getImeEventActionName() {
        return mImeEventActionName.get();
    }

    private final AtomicReference<String> mClientPackageName = new AtomicReference<>();

    @Nullable
    String getClientPackageName() {
        return mClientPackageName.get();
    }

    private class MockInputMethodImpl extends InputMethodImpl {
        @Override
        public void showSoftInput(int flags, ResultReceiver resultReceiver) {
            getTracer().showSoftInput(flags, resultReceiver,
                    () -> super.showSoftInput(flags, resultReceiver));
        }

        @Override
        public void hideSoftInput(int flags, ResultReceiver resultReceiver) {
            getTracer().hideSoftInput(flags, resultReceiver,
                    () -> super.hideSoftInput(flags, resultReceiver));
        }

        @Override
        public void attachToken(IBinder token) {
            getTracer().attachToken(token, () -> super.attachToken(token));
        }

        @Override
        public void bindInput(InputBinding binding) {
            getTracer().bindInput(binding, () -> super.bindInput(binding));
        }

        @Override
        public void unbindInput() {
            getTracer().unbindInput(() -> super.unbindInput());
        }
    }

    @Override
    public void onCreate() {
        // Initialize minimum settings to send events in Tracer#onCreate().
        mSettings = SettingsProvider.getSettings();
        if (mSettings == null) {
            throw new IllegalStateException("Settings file is not found. "
                    + "Make sure MockImeSession.create() is used to launch Mock IME.");
        }
        mClientPackageName.set(mSettings.getClientPackageName());
        mImeEventActionName.set(mSettings.getEventCallbackActionName());

        getTracer().onCreate(() -> {
            super.onCreate();
            mHandlerThread.start();
            final String actionName = getCommandActionName(mSettings.getEventCallbackActionName());
            mCommandReceiver = new CommandReceiver(actionName, this::onReceiveCommand);
            final IntentFilter filter = new IntentFilter(actionName);
            final Handler handler = new Handler(mHandlerThread.getLooper());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(mCommandReceiver, filter, null /* broadcastPermission */, handler,
                        Context.RECEIVER_VISIBLE_TO_INSTANT_APPS);
            } else {
                registerReceiver(mCommandReceiver, filter, null /* broadcastPermission */, handler);
            }

            final int windowFlags = mSettings.getWindowFlags(0);
            final int windowFlagsMask = mSettings.getWindowFlagsMask(0);
            if (windowFlags != 0 || windowFlagsMask != 0) {
                final int prevFlags = getWindow().getWindow().getAttributes().flags;
                getWindow().getWindow().setFlags(windowFlags, windowFlagsMask);
                // For some reasons, seems that we need to post another requestLayout() when
                // FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS bit is changed.
                // TODO: Investigate the reason.
                if ((windowFlagsMask & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0) {
                    final boolean hadFlag = (prevFlags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
                    final boolean hasFlag = (windowFlags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
                    if (hadFlag != hasFlag) {
                        final View decorView = getWindow().getWindow().getDecorView();
                        decorView.post(() -> decorView.requestLayout());
                    }
                }
            }

            // Ensuring bar contrast interferes with the tests.
            getWindow().getWindow().setStatusBarContrastEnforced(false);
            getWindow().getWindow().setNavigationBarContrastEnforced(false);

            if (mSettings.hasNavigationBarColor()) {
                getWindow().getWindow().setNavigationBarColor(mSettings.getNavigationBarColor());
            }
        });
    }

    @Override
    public void onConfigureWindow(Window win, boolean isFullscreen, boolean isCandidatesOnly) {
        getTracer().onConfigureWindow(win, isFullscreen, isCandidatesOnly,
                () -> super.onConfigureWindow(win, isFullscreen, isCandidatesOnly));
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return getTracer().onEvaluateFullscreenMode(() ->
                mSettings.fullscreenModeAllowed(false) && super.onEvaluateFullscreenMode());
    }

    private static final class KeyboardLayoutView extends LinearLayout {
        @NonNull
        private final ImeSettings mSettings;
        @NonNull
        private final View.OnLayoutChangeListener mLayoutListener;

        KeyboardLayoutView(Context context, @NonNull ImeSettings imeSettings,
                @Nullable Consumer<ImeLayoutInfo> onInputViewLayoutChangedCallback) {
            super(context);

            mSettings = imeSettings;

            setOrientation(VERTICAL);

            final int defaultBackgroundColor =
                    getResources().getColor(android.R.color.holo_orange_dark, null);
            setBackgroundColor(mSettings.getBackgroundColor(defaultBackgroundColor));

            final int mainSpacerHeight = mSettings.getInputViewHeightWithoutSystemWindowInset(
                    LayoutParams.WRAP_CONTENT);
            {
                final RelativeLayout layout = new RelativeLayout(getContext());
                final TextView textView = new TextView(getContext());
                final RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT,
                        RelativeLayout.LayoutParams.WRAP_CONTENT);
                params.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                textView.setLayoutParams(params);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                textView.setGravity(Gravity.CENTER);
                textView.setText(getImeId());
                layout.addView(textView);
                addView(layout, LayoutParams.MATCH_PARENT, mainSpacerHeight);
            }

            final int systemUiVisibility = mSettings.getInputViewSystemUiVisibility(0);
            if (systemUiVisibility != 0) {
                setSystemUiVisibility(systemUiVisibility);
            }

            mLayoutListener = (View v, int left, int top, int right, int bottom, int oldLeft,
                    int oldTop, int oldRight, int oldBottom) ->
                    onInputViewLayoutChangedCallback.accept(
                            ImeLayoutInfo.fromLayoutListenerCallback(
                                    v, left, top, right, bottom, oldLeft, oldTop, oldRight,
                                    oldBottom));
            this.addOnLayoutChangeListener(mLayoutListener);
        }

        private void updateBottomPaddingIfNecessary(int newPaddingBottom) {
            if (getPaddingBottom() != newPaddingBottom) {
                setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), newPaddingBottom);
            }
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            if (insets.isConsumed()
                    || (getSystemUiVisibility() & SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) == 0) {
                // In this case we are not interested in consuming NavBar region.
                // Make sure that the bottom padding is empty.
                updateBottomPaddingIfNecessary(0);
                return insets;
            }

            // In some cases the bottom system window inset is not a navigation bar. Wear devices
            // that have bottom chin are examples.  For now, assume that it's a navigation bar if it
            // has the same height as the root window's stable bottom inset.
            final WindowInsets rootWindowInsets = getRootWindowInsets();
            if (rootWindowInsets != null && (rootWindowInsets.getStableInsetBottom()
                    != insets.getSystemWindowInsetBottom())) {
                // This is probably not a NavBar.
                updateBottomPaddingIfNecessary(0);
                return insets;
            }

            final int possibleNavBarHeight = insets.getSystemWindowInsetBottom();
            updateBottomPaddingIfNecessary(possibleNavBarHeight);
            return possibleNavBarHeight <= 0
                    ? insets
                    : insets.replaceSystemWindowInsets(
                            insets.getSystemWindowInsetLeft(),
                            insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(),
                            0 /* bottom */);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeOnLayoutChangeListener(mLayoutListener);
        }
    }

    private void onInputViewLayoutChanged(@NonNull ImeLayoutInfo layoutInfo) {
        getTracer().onInputViewLayoutChanged(layoutInfo, () -> { });
    }

    @Override
    public View onCreateInputView() {
        return getTracer().onCreateInputView(() ->
                new KeyboardLayoutView(this, mSettings, this::onInputViewLayoutChanged));
    }

    @Override
    public void onStartInput(EditorInfo editorInfo, boolean restarting) {
        getTracer().onStartInput(editorInfo, restarting,
                () -> super.onStartInput(editorInfo, restarting));
    }

    @Override
    public void onStartInputView(EditorInfo editorInfo, boolean restarting) {
        getTracer().onStartInputView(editorInfo, restarting,
                () -> super.onStartInputView(editorInfo, restarting));
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        getTracer().onFinishInputView(finishingInput,
                () -> super.onFinishInputView(finishingInput));
    }

    @Override
    public void onFinishInput() {
        getTracer().onFinishInput(() -> super.onFinishInput());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return getTracer().onKeyDown(keyCode, event, () -> super.onKeyDown(keyCode, event));
    }

    @Override
    public void onUpdateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
        getTracer().onUpdateCursorAnchorInfo(cursorAnchorInfo,
                () -> super.onUpdateCursorAnchorInfo(cursorAnchorInfo));
    }

    @CallSuper
    public boolean onEvaluateInputViewShown() {
        return getTracer().onEvaluateInputViewShown(() -> {
            // onShowInputRequested() is indeed @CallSuper so we always call this, even when the
            // result is ignored.
            final boolean originalResult = super.onEvaluateInputViewShown();
            if (!mSettings.getHardKeyboardConfigurationBehaviorAllowed(false)) {
                final Configuration config = getResources().getConfiguration();
                if (config.keyboard != Configuration.KEYBOARD_NOKEYS
                        && config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES) {
                    // Override the behavior of InputMethodService#onEvaluateInputViewShown()
                    return true;
                }
            }
            return originalResult;
        });
    }

    @Override
    public boolean onShowInputRequested(int flags, boolean configChange) {
        return getTracer().onShowInputRequested(flags, configChange, () -> {
            // onShowInputRequested() is not marked with @CallSuper, but just in case.
            final boolean originalResult = super.onShowInputRequested(flags, configChange);
            if (!mSettings.getHardKeyboardConfigurationBehaviorAllowed(false)) {
                if ((flags & InputMethod.SHOW_EXPLICIT) == 0
                        && getResources().getConfiguration().keyboard
                        != Configuration.KEYBOARD_NOKEYS) {
                    // Override the behavior of InputMethodService#onShowInputRequested()
                    return true;
                }
            }
            return originalResult;
        });
    }

    @Override
    public void onDestroy() {
        getTracer().onDestroy(() -> {
            super.onDestroy();
            unregisterReceiver(mCommandReceiver);
            mHandlerThread.quitSafely();
        });
    }

    @Override
    public AbstractInputMethodImpl onCreateInputMethodInterface() {
        return getTracer().onCreateInputMethodInterface(() -> new MockInputMethodImpl());
    }

    private final ThreadLocal<Tracer> mThreadLocalTracer = new ThreadLocal<>();

    private Tracer getTracer() {
        Tracer tracer = mThreadLocalTracer.get();
        if (tracer == null) {
            tracer = new Tracer(this);
            mThreadLocalTracer.set(tracer);
        }
        return tracer;
    }

    @NonNull
    private ImeState getState() {
        final boolean hasInputBinding = getCurrentInputBinding() != null;
        final boolean hasDummyInputConnectionConnection =
                !hasInputBinding
                        || getCurrentInputConnection() == getCurrentInputBinding().getConnection();
        return new ImeState(hasInputBinding, hasDummyInputConnectionConnection);
    }

    /**
     * Event tracing helper class for {@link MockIme}.
     */
    private static final class Tracer {

        @NonNull
        private final MockIme mIme;

        private final int mThreadId = Process.myTid();

        @NonNull
        private final String mThreadName =
                Thread.currentThread().getName() != null ? Thread.currentThread().getName() : "";

        private final boolean mIsMainThread =
                Looper.getMainLooper().getThread() == Thread.currentThread();

        private int mNestLevel = 0;

        private String mImeEventActionName;

        private String mClientPackageName;

        Tracer(@NonNull MockIme mockIme) {
            mIme = mockIme;
        }

        private void sendEventInternal(@NonNull ImeEvent event) {
            if (mImeEventActionName == null) {
                mImeEventActionName = mIme.getImeEventActionName();
            }
            if (mClientPackageName == null) {
                mClientPackageName = mIme.getClientPackageName();
            }
            if (mImeEventActionName == null || mClientPackageName == null) {
                Log.e(TAG, "Tracer cannot be used before onCreate()");
                return;
            }
            final Intent intent = new Intent()
                    .setAction(mImeEventActionName)
                    .setPackage(mClientPackageName)
                    .putExtras(event.toBundle())
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
            mIme.sendBroadcast(intent);
        }

        private void recordEventInternal(@NonNull String eventName, @NonNull Runnable runnable) {
            recordEventInternal(eventName, runnable, new Bundle());
        }

        private void recordEventInternal(@NonNull String eventName, @NonNull Runnable runnable,
                @NonNull Bundle arguments) {
            recordEventInternal(eventName, () -> {
                runnable.run(); return ImeEvent.RETURN_VALUE_UNAVAILABLE;
            }, arguments);
        }

        private <T> T recordEventInternal(@NonNull String eventName,
                @NonNull Supplier<T> supplier) {
            return recordEventInternal(eventName, supplier, new Bundle());
        }

        private <T> T recordEventInternal(@NonNull String eventName,
                @NonNull Supplier<T> supplier, @NonNull Bundle arguments) {
            final ImeState enterState = mIme.getState();
            final long enterTimestamp = SystemClock.elapsedRealtimeNanos();
            final long enterWallTime = System.currentTimeMillis();
            final int nestLevel = mNestLevel;
            // Send enter event
            sendEventInternal(new ImeEvent(eventName, nestLevel, mThreadName,
                    mThreadId, mIsMainThread, enterTimestamp, 0, enterWallTime,
                    0, enterState, null, arguments,
                    ImeEvent.RETURN_VALUE_UNAVAILABLE));
            ++mNestLevel;
            T result;
            try {
                result = supplier.get();
            } finally {
                --mNestLevel;
            }
            final long exitTimestamp = SystemClock.elapsedRealtimeNanos();
            final long exitWallTime = System.currentTimeMillis();
            final ImeState exitState = mIme.getState();
            // Send exit event
            sendEventInternal(new ImeEvent(eventName, nestLevel, mThreadName,
                    mThreadId, mIsMainThread, enterTimestamp, exitTimestamp, enterWallTime,
                    exitWallTime, enterState, exitState, arguments, result));
            return result;
        }

        public void onCreate(@NonNull Runnable runnable) {
            recordEventInternal("onCreate", runnable);
        }

        public void onConfigureWindow(Window win, boolean isFullscreen,
                boolean isCandidatesOnly, @NonNull Runnable runnable) {
            final Bundle arguments = new Bundle();
            arguments.putBoolean("isFullscreen", isFullscreen);
            arguments.putBoolean("isCandidatesOnly", isCandidatesOnly);
            recordEventInternal("onConfigureWindow", runnable, arguments);
        }

        public boolean onEvaluateFullscreenMode(@NonNull BooleanSupplier supplier) {
            return recordEventInternal("onEvaluateFullscreenMode", supplier::getAsBoolean);
        }

        public boolean onEvaluateInputViewShown(@NonNull BooleanSupplier supplier) {
            return recordEventInternal("onEvaluateInputViewShown", supplier::getAsBoolean);
        }

        public View onCreateInputView(@NonNull Supplier<View> supplier) {
            return recordEventInternal("onCreateInputView", supplier);
        }

        public void onStartInput(EditorInfo editorInfo, boolean restarting,
                @NonNull Runnable runnable) {
            final Bundle arguments = new Bundle();
            arguments.putParcelable("editorInfo", editorInfo);
            arguments.putBoolean("restarting", restarting);
            recordEventInternal("onStartInput", runnable, arguments);
        }

        public void onStartInputView(EditorInfo editorInfo, boolean restarting,
                @NonNull Runnable runnable) {
            final Bundle arguments = new Bundle();
            arguments.putParcelable("editorInfo", editorInfo);
            arguments.putBoolean("restarting", restarting);
            recordEventInternal("onStartInputView", runnable, arguments);
        }

        public void onFinishInputView(boolean finishingInput, @NonNull Runnable runnable) {
            final Bundle arguments = new Bundle();
            arguments.putBoolean("finishingInput", finishingInput);
            recordEventInternal("onFinishInputView", runnable, arguments);
        }

        public void onFinishInput(@NonNull Runnable runnable) {
            recordEventInternal("onFinishInput", runnable);
        }

        public boolean onKeyDown(int keyCode, KeyEvent event, @NonNull BooleanSupplier supplier) {
            final Bundle arguments = new Bundle();
            arguments.putInt("keyCode", keyCode);
            arguments.putParcelable("event", event);
            return recordEventInternal("onKeyDown", supplier::getAsBoolean, arguments);
        }

        public void onUpdateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo,
                @NonNull Runnable runnable) {
            final Bundle arguments = new Bundle();
            arguments.putParcelable("cursorAnchorInfo", cursorAnchorInfo);
            recordEventInternal("onUpdateCursorAnchorInfo", runnable, arguments);
        }

        public boolean onShowInputRequested(int flags, boolean configChange,
                @NonNull BooleanSupplier supplier) {
            final Bundle arguments = new Bundle();
            arguments.putInt("flags", flags);
            arguments.putBoolean("configChange", configChange);
            return recordEventInternal("onShowInputRequested", supplier::getAsBoolean, arguments);
        }

        public void onDestroy(@NonNull Runnable runnable) {
            recordEventInternal("onDestroy", runnable);
        }

        public void attachToken(IBinder token, @NonNull Runnable runnable) {
            final Bundle arguments = new Bundle();
            arguments.putBinder("token", token);
            recordEventInternal("attachToken", runnable, arguments);
        }

        public void bindInput(InputBinding binding, @NonNull Runnable runnable) {
            final Bundle arguments = new Bundle();
            arguments.putParcelable("binding", binding);
            recordEventInternal("bindInput", runnable, arguments);
        }

        public void unbindInput(@NonNull Runnable runnable) {
            recordEventInternal("unbindInput", runnable);
        }

        public void showSoftInput(int flags, ResultReceiver resultReceiver,
                @NonNull Runnable runnable) {
            final Bundle arguments = new Bundle();
            arguments.putInt("flags", flags);
            arguments.putParcelable("resultReceiver", resultReceiver);
            recordEventInternal("showSoftInput", runnable, arguments);
        }

        public void hideSoftInput(int flags, ResultReceiver resultReceiver,
                @NonNull Runnable runnable) {
            final Bundle arguments = new Bundle();
            arguments.putInt("flags", flags);
            arguments.putParcelable("resultReceiver", resultReceiver);
            recordEventInternal("hideSoftInput", runnable, arguments);
        }

        public AbstractInputMethodImpl onCreateInputMethodInterface(
                @NonNull Supplier<AbstractInputMethodImpl> supplier) {
            return recordEventInternal("onCreateInputMethodInterface", supplier);
        }

        public void onReceiveCommand(
                @NonNull ImeCommand command, @NonNull Runnable runnable) {
            final Bundle arguments = new Bundle();
            arguments.putBundle("command", command.toBundle());
            recordEventInternal("onReceiveCommand", runnable, arguments);
        }

        public void onHandleCommand(
                @NonNull ImeCommand command, @NonNull Supplier<Object> resultSupplier) {
            final Bundle arguments = new Bundle();
            arguments.putBundle("command", command.toBundle());
            recordEventInternal("onHandleCommand", resultSupplier, arguments);
        }

        public void onInputViewLayoutChanged(@NonNull ImeLayoutInfo imeLayoutInfo,
                @NonNull Runnable runnable) {
            final Bundle arguments = new Bundle();
            imeLayoutInfo.writeToBundle(arguments);
            recordEventInternal("onInputViewLayoutChanged", runnable, arguments);
        }
    }
}
