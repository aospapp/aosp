/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.car.rotary;

import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED;
import static android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED;
import static android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
import static android.view.accessibility.AccessibilityEvent.WINDOWS_CHANGE_REMOVED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.car.Car;
import android.car.input.CarInputManager;
import android.car.input.RotaryEvent;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.ui.utils.DirectManipulationHelper;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A service that can change focus based on rotary controller rotation and nudges, and perform
 * clicks based on rotary controller center button clicks.
 * <p>
 * As an {@link AccessibilityService}, this service responds to {@link KeyEvent}s (on debug builds
 * only) and {@link AccessibilityEvent}s.
 * <p>
 * On debug builds, {@link KeyEvent}s coming from the keyboard are handled by clicking the view, or
 * moving the focus, sometimes within a window and sometimes between windows.
 * <p>
 * This service listens to two types of {@link AccessibilityEvent}s: {@link
 * AccessibilityEvent#TYPE_VIEW_FOCUSED} and {@link AccessibilityEvent#TYPE_VIEW_CLICKED}. The
 * former is used to keep {@link #mFocusedNode} up to date as the focus changes. The latter is used
 * to detect when the user switches from rotary mode to touch mode and to keep {@link
 * #mLastTouchedNode} up to date.
 * <p>
 * As a {@link CarInputManager.CarInputCaptureCallback}, this service responds to {@link KeyEvent}s
 * and {@link RotaryEvent}s, both of which are coming from the controller.
 * <p>
 * {@link KeyEvent}s are handled by clicking the view, or moving the focus, sometimes within a
 * window and sometimes between windows.
 * <p>
 * {@link RotaryEvent}s are handled by moving the focus within the same {@link
 * com.android.car.ui.FocusArea}.
 * <p>
 * Note: onFoo methods are all called on the main thread so no locks are needed.
 */
public class RotaryService extends AccessibilityService implements
        CarInputManager.CarInputCaptureCallback {

    /*
     * Whether to treat the application window as system window for direct manipulation mode. Set it
     * to {@code true} for testing only.
     */
    private static final boolean TREAT_APP_WINDOW_AS_SYSTEM_WINDOW = false;

    /**
     * How many detents to rotate when the user holds in shift while pressing C, V, Q, or E on a
     * debug build.
     */
    private static final int SHIFT_DETENTS = 10;

    @NonNull
    private NodeCopier mNodeCopier = new NodeCopier();

    private Navigator mNavigator;

    /** Input types to capture. */
    private final int[] mInputTypes = new int[]{
            // Capture controller rotation.
            CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION,
            // Capture controller center button clicks.
            CarInputManager.INPUT_TYPE_DPAD_KEYS,
            // Capture controller nudges.
            CarInputManager.INPUT_TYPE_SYSTEM_NAVIGATE_KEYS};

    /**
     * Time interval in milliseconds to decide whether we should accelerate the rotation by 3 times
     * for a rotate event.
     */
    private int mRotationAcceleration3xMs;

    /**
     * Time interval in milliseconds to decide whether we should accelerate the rotation by 2 times
     * for a rotate event.
     */
    private int mRotationAcceleration2xMs;

    /** Whether to clear focus area history when the user rotates the controller. */
    private boolean mClearFocusAreaHistoryWhenRotating;

    /**
     * The currently focused node, if any. It's null if no nodes are focused or a {@link
     * com.android.car.ui.FocusParkingView} is focused.
     */
    private AccessibilityNodeInfo mFocusedNode = null;

    /**
     * The previously focused node, if any. It's null if no nodes were focused or a {@link
     * com.android.car.ui.FocusParkingView} was focused.
     */
    private AccessibilityNodeInfo mPreviousFocusedNode = null;

    /**
     * The currently focused {@link com.android.car.ui.FocusParkingView} that was focused by us to
     * clear the focus, if any.
     */
    private AccessibilityNodeInfo mFocusParkingView = null;

    /**
     * The current scrollable container, if any. Either {@link #mFocusedNode} or an ancestor of it.
     */
    private AccessibilityNodeInfo mScrollableContainer = null;

    /**
     * The last clicked node by touching the screen, if any were clicked since we last navigated.
     */
    private AccessibilityNodeInfo mLastTouchedNode = null;

    /**
     * How many milliseconds to ignore {@link AccessibilityEvent#TYPE_VIEW_CLICKED} events after
     * performing {@link AccessibilityNodeInfo#ACTION_CLICK} or injecting a {@link
     * KeyEvent#KEYCODE_DPAD_CENTER} event.
     */
    private int mIgnoreViewClickedMs;

    /**
     * When not {@code null}, {@link AccessibilityEvent#TYPE_VIEW_CLICKED} events with this node
     * are ignored if they occur before {@link #mIgnoreViewClickedUntil}.
     */
    private AccessibilityNodeInfo mIgnoreViewClickedNode;

    /**
     * When to stop ignoring {@link AccessibilityEvent#TYPE_VIEW_CLICKED} events for {@link
     * #mIgnoreViewClickedNode} in {@link SystemClock#uptimeMillis}.
     */
    private long mIgnoreViewClickedUntil;

    /**
     * Possible actions to do after receiving {@link AccessibilityEvent#TYPE_VIEW_SCROLLED}.
     *
     * @see #injectScrollEvent
     */
    private enum AfterScrollAction {
        /** Do nothing. */
        NONE,
        /**
         * Focus the view before the focused view in Tab order in the scrollable container, if any.
         */
        FOCUS_PREVIOUS,
        /**
         * Focus the view after the focused view in Tab order in the scrollable container, if any.
         */
        FOCUS_NEXT,
        /** Focus the first view in the scrollable container, if any. */
        FOCUS_FIRST,
        /** Focus the last view in the scrollable container, if any. */
        FOCUS_LAST,
    }

    private AfterScrollAction mAfterScrollAction = AfterScrollAction.NONE;

    /**
     * How many milliseconds to wait for a {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event after
     * scrolling.
     */
    private int mAfterScrollTimeoutMs;

    /**
     * When to give up on receiving {@link AccessibilityEvent#TYPE_VIEW_SCROLLED}, in
     * {@link SystemClock#uptimeMillis}.
     */
    private long mAfterScrollActionUntil;

    /** Whether we're in rotary mode (vs touch mode). */
    private boolean mInRotaryMode;

    /** Whether we're in direct manipulation mode. */
    private boolean mInDirectManipulationMode;

    /** The {@link SystemClock#uptimeMillis} when the last rotary rotation event occurred. */
    private long mLastRotateEventTime;

    /**
     * The repeat count of {@link KeyEvent#KEYCODE_DPAD_CENTER}. Use to prevent processing a center
     * button click when the center button is released after a long press.
     */
    private int mCenterButtonRepeatCount;

    private static final Map<Integer, Integer> TEST_TO_REAL_KEYCODE_MAP;

    private static final Map<Integer, Integer> DIRECTION_TO_KEYCODE_MAP;

    static {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT);
        map.put(KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT);
        map.put(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP);
        map.put(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN);
        map.put(KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_DPAD_CENTER);
        map.put(KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_BACK);
        // Legacy map
        map.put(KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT);
        map.put(KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT);
        map.put(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP);
        map.put(KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN);
        map.put(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_DPAD_CENTER);
        map.put(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK);

        TEST_TO_REAL_KEYCODE_MAP = Collections.unmodifiableMap(map);
    }

    static {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(View.FOCUS_UP, KeyEvent.KEYCODE_DPAD_UP);
        map.put(View.FOCUS_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
        map.put(View.FOCUS_LEFT, KeyEvent.KEYCODE_DPAD_LEFT);
        map.put(View.FOCUS_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT);

        DIRECTION_TO_KEYCODE_MAP = Collections.unmodifiableMap(map);
    }

    private Car mCar;
    private CarInputManager mCarInputManager;
    private InputManager mInputManager;

    /** Package name of foreground app. */
    private CharSequence mForegroundApp;

    private WindowManager mWindowManager;

    @Override
    public void onCreate() {
        super.onCreate();
        Resources res = getResources();
        mRotationAcceleration3xMs = res.getInteger(R.integer.rotation_acceleration_3x_ms);
        mRotationAcceleration2xMs = res.getInteger(R.integer.rotation_acceleration_2x_ms);

        mClearFocusAreaHistoryWhenRotating =
                res.getBoolean(R.bool.clear_focus_area_history_when_rotating);

        @RotaryCache.CacheType int focusHistoryCacheType =
                res.getInteger(R.integer.focus_history_cache_type);
        int focusHistoryCacheSize =
                res.getInteger(R.integer.focus_history_cache_size);
        int focusHistoryExpirationTimeMs =
                res.getInteger(R.integer.focus_history_expiration_time_ms);

        @RotaryCache.CacheType int focusAreaHistoryCacheType =
                res.getInteger(R.integer.focus_area_history_cache_type);
        int focusAreaHistoryCacheSize =
                res.getInteger(R.integer.focus_area_history_cache_size);
        int focusAreaHistoryExpirationTimeMs =
                res.getInteger(R.integer.focus_area_history_expiration_time_ms);

        @RotaryCache.CacheType int focusWindowCacheType =
                res.getInteger(R.integer.focus_window_cache_type);
        int focusWindowCacheSize =
                res.getInteger(R.integer.focus_window_cache_size);
        int focusWindowExpirationTimeMs =
                res.getInteger(R.integer.focus_window_expiration_time_ms);

        int hunMarginHorizontal =
                res.getDimensionPixelSize(R.dimen.notification_headsup_card_margin_horizontal);
        int hunLeft = hunMarginHorizontal;
        WindowManager windowManager = getSystemService(WindowManager.class);
        int displayWidth = windowManager.getCurrentWindowMetrics().getBounds().width();
        int hunRight = displayWidth - hunMarginHorizontal;
        boolean showHunOnBottom = res.getBoolean(R.bool.config_showHeadsUpNotificationOnBottom);

        mIgnoreViewClickedMs = res.getInteger(R.integer.ignore_view_clicked_ms);
        mAfterScrollTimeoutMs = res.getInteger(R.integer.after_scroll_timeout_ms);

        mNavigator = new Navigator(
                focusHistoryCacheType,
                focusHistoryCacheSize,
                focusHistoryExpirationTimeMs,
                focusAreaHistoryCacheType,
                focusAreaHistoryCacheSize,
                focusAreaHistoryExpirationTimeMs,
                focusWindowCacheType,
                focusWindowCacheSize,
                focusWindowExpirationTimeMs,
                hunLeft,
                hunRight,
                showHunOnBottom);
    }

    /**
     * {@inheritDoc}
     * <p>
     * We need to access WindowManager in onCreate() and
     * IAccessibilityServiceClientWrapper.Callbacks#init(). Since WindowManager is a visual
     * service, only Activity or other visual Context can access it. So we create a window context
     * (a visual context) and delegate getSystemService() to it.
     */
    @Override
    public Object getSystemService(@ServiceName @NonNull String name) {
        // Guarantee that we always return the same WindowManager instance.
        if (WINDOW_SERVICE.equals(name)) {
            if (mWindowManager == null) {
                // We need to set the display before creating the WindowContext.
                DisplayManager displayManager = getSystemService(DisplayManager.class);
                Display primaryDisplay = displayManager.getDisplay(DEFAULT_DISPLAY);
                updateDisplay(primaryDisplay.getDisplayId());

                Context windowContext = createWindowContext(TYPE_APPLICATION_OVERLAY, null);
                mWindowManager = (WindowManager) windowContext.getSystemService(WINDOW_SERVICE);
            }
            return mWindowManager;
        }
        return super.getSystemService(name);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        mCar = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    mCar = car;
                    if (ready) {
                        mCarInputManager =
                                (CarInputManager) mCar.getCarManager(Car.CAR_INPUT_SERVICE);
                        mCarInputManager.requestInputEventCapture(this,
                                CarInputManager.TARGET_DISPLAY_TYPE_MAIN,
                                mInputTypes,
                                CarInputManager.CAPTURE_REQ_FLAGS_ALLOW_DELAYED_GRANT);
                    }
                });

        if (Build.IS_DEBUGGABLE) {
            AccessibilityServiceInfo serviceInfo = getServiceInfo();
            // Filter testing KeyEvents from a keyboard.
            serviceInfo.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(serviceInfo);
        }

        mInputManager = getSystemService(InputManager.class);
    }

    @Override
    public void onInterrupt() {
        L.v("onInterrupt()");
    }

    @Override
    public void onDestroy() {
        if (mCarInputManager != null) {
            mCarInputManager.releaseInputEventCapture(CarInputManager.TARGET_DISPLAY_TYPE_MAIN);
        }
        if (mCar != null) {
            mCar.disconnect();
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        switch (event.getEventType()) {
            case TYPE_VIEW_FOCUSED: {
                handleViewFocusedEvent(event);
                break;
            }
            case TYPE_VIEW_CLICKED: {
                handleViewClickedEvent(event);
                break;
            }
            case TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                updateDirectManipulationMode(event, true);
                break;
            }
            case TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                updateDirectManipulationMode(event, false);
                break;
            }
            case TYPE_VIEW_SCROLLED: {
                handleViewScrolledEvent(event);
                break;
            }
            case TYPE_WINDOW_STATE_CHANGED: {
                CharSequence packageName = event.getPackageName();
                onForegroundAppChanged(packageName);
                break;
            }
            case TYPE_WINDOWS_CHANGED: {
                handleWindowsChangedEvent(event);
                break;
            }
            default:
                // Do nothing.
        }
    }

    /**
     * Callback of {@link AccessibilityService}. It allows us to observe testing {@link KeyEvent}s
     * from keyboard, including keys "C" and "V" to emulate controller rotation, keys "J" "L" "I"
     * "K" to emulate controller nudges, and key "Comma" to emulate center button clicks.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (Build.IS_DEBUGGABLE) {
            return handleKeyEvent(event);
        }
        return false;
    }

    /**
     * Callback of {@link CarInputManager.CarInputCaptureCallback}. It allows us to capture {@link
     * KeyEvent}s generated by a navigation controller, such as controller nudge and controller
     * click events.
     */
    @Override
    public void onKeyEvents(int targetDisplayId, List<KeyEvent> events) {
        if (!isValidDisplayId(targetDisplayId)) {
            return;
        }
        for (KeyEvent event : events) {
            handleKeyEvent(event);
        }
    }

    /**
     * Callback of {@link CarInputManager.CarInputCaptureCallback}. It allows us to capture {@link
     * RotaryEvent}s generated by a navigation controller.
     */
    @Override
    public void onRotaryEvents(int targetDisplayId, List<RotaryEvent> events) {
        if (!isValidDisplayId(targetDisplayId)) {
            return;
        }
        for (RotaryEvent rotaryEvent : events) {
            handleRotaryEvent(rotaryEvent);
        }
    }

    @Override
    public void onCaptureStateChanged(int targetDisplayId,
            @android.annotation.NonNull @CarInputManager.InputTypeEnum int[] activeInputTypes) {
        // Do nothing.
    }

    private static boolean isValidDisplayId(int displayId) {
        if (displayId == CarInputManager.TARGET_DISPLAY_TYPE_MAIN) {
            return true;
        }
        L.e("RotaryService shouldn't capture events from display ID " + displayId);
        return false;
    }

    /**
     * Handles key events. Returns whether the key event was consumed. To avoid invalid event stream
     * getting through to the application, if a key down event is consumed, the corresponding key up
     * event must be consumed too, and vice versa.
     */
    private boolean handleKeyEvent(KeyEvent event) {
        int action = event.getAction();
        boolean isActionDown = action == KeyEvent.ACTION_DOWN;
        int keyCode = getKeyCode(event);
        int detents = event.isShiftPressed() ? SHIFT_DETENTS : 1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_Q:
            case KeyEvent.KEYCODE_C:
                if (isActionDown) {
                    handleRotateEvent(/* clockwise= */ false, detents,
                            event.getEventTime());
                }
                return true;
            case KeyEvent.KEYCODE_E:
            case KeyEvent.KEYCODE_V:
                if (isActionDown) {
                    handleRotateEvent(/* clockwise= */ true, detents,
                            event.getEventTime());
                }
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT:
                handleNudgeEvent(View.FOCUS_LEFT, action);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT:
                handleNudgeEvent(View.FOCUS_RIGHT, action);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP:
                handleNudgeEvent(View.FOCUS_UP, action);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN:
                handleNudgeEvent(View.FOCUS_DOWN, action);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (isActionDown) {
                    mCenterButtonRepeatCount = event.getRepeatCount();
                }
                if (mCenterButtonRepeatCount == 0) {
                    handleCenterButtonEvent(action, /* longClick= */ false);
                } else if (mCenterButtonRepeatCount == 1) {
                    handleCenterButtonEvent(action, /* longClick= */ true);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (mInDirectManipulationMode) {
                    handleBackButtonEvent(action);
                    return true;
                }
                return false;
            default:
                // Do nothing
        }
        return false;
    }

    /** Handles {@link AccessibilityEvent#TYPE_VIEW_FOCUSED} event. */
    private void handleViewFocusedEvent(@NonNull AccessibilityEvent event) {
        // A view was focused. We ignore focus changes in touch mode. We don't use
        // TYPE_VIEW_FOCUSED to keep mLastTouchedNode up to date because most views can't be
        // focused in touch mode. In rotary mode, we use TYPE_VIEW_FOCUSED events to keep
        // mFocusedNode up to date, to clear the focus when moving between windows, to detect when
        // a scrollable container scrolls and pushes the focused descendant out of the viewport,
        // and to detect when the focused view is removed.
        if (!mInRotaryMode) {
            return;
        }
        AccessibilityNodeInfo sourceNode = event.getSource();
        // No need to handle TYPE_VIEW_FOCUSED event if sourceNode is null or the focused node stays
        // the same.
        if (sourceNode == null || sourceNode.equals(mFocusedNode)) {
            Utils.recycleNode(sourceNode);
            return;
        }
        // Case 1: the focused view is not a FocusParkingView. In this case we just update
        // mFocusedNode.
        if (!Utils.isFocusParkingView(sourceNode)) {
            // Android doesn't clear focus automatically when focus is set in another window.
            maybeClearFocusInCurrentWindow(sourceNode);
            setFocusedNode(sourceNode);
        }
        // Case 2: the focused view is a FocusParkingView and it was focused by us to clear the
        // focus in another window. In this case we should do nothing but reset mFocusParkingView.
        else if (sourceNode.equals(mFocusParkingView)) {
            Utils.recycleNode(mFocusParkingView);
            mFocusParkingView = null;
        }
        // Case 3: the focused view is a FocusParkingView and it was focused when scrolling pushed
        // the focused view out of the viewport. When this happens, focus the scrollable container.
        else if (mFocusedNode != null && mScrollableContainer != null) {
            mScrollableContainer = Utils.refreshNode(mScrollableContainer);
            if (mScrollableContainer != null) {
                L.d("Moving focus from FocusParkingView to scrollable container");
                performFocusAction(mScrollableContainer);
            } else {
                L.d("mScrollableContainer is not in the view tree");
            }
        }
        // Case 4 (all other cases): the focused view is a FocusParkingView and it's none
        // of the cases above. For example:
        // 1. When the previously focused view is removed by the app, Android will focus on
        //    the first focusable view in the window, which is the FocusParkingView.
        // 2. When a dialog window shows up, Android will focus on the first focusable view
        //    in the dialog window, which is the FocusParkingView.
        // In both cases we should try to move focus to another view nearby.
        else {
            moveFocusToNearbyView(sourceNode);
        }

        // Recycle sourceNode no matter in which case above.
        Utils.recycleNode(sourceNode);
    }

    /** Handles {@link AccessibilityEvent#TYPE_VIEW_CLICKED} event. */
    private void handleViewClickedEvent(@NonNull AccessibilityEvent event) {
        // A view was clicked. If we triggered the click via performAction(ACTION_CLICK) or
        // by injecting KEYCODE_DPAD_CENTER, we ignore it. Otherwise, we assume the user
        // touched the screen. In this case, we exit rotary mode if necessary, update
        // mLastTouchedNode, and clear the focus if the user touched a view in a different
        // window.
        // To decide whether the click was triggered by us, we can compare the source node
        // in the event with mIgnoreViewClickedNode. If they're equal, the click was
        // triggered by us. But there is a corner case. If a dialog shows up after we
        // clicked the view, the window containing the view will be removed. We still
        // receive click event (TYPE_VIEW_CLICKED) but the source node in the event will be
        // null.
        // Note: there is no way to tell whether the window is removed in click event
        // because window remove event (TYPE_WINDOWS_CHANGED with type
        // WINDOWS_CHANGE_REMOVED) comes AFTER click event.
        AccessibilityNodeInfo sourceNode = event.getSource();
        if (mIgnoreViewClickedNode != null
                && event.getEventTime() < mIgnoreViewClickedUntil
                && ((sourceNode == null) || mIgnoreViewClickedNode.equals(sourceNode))) {
            setIgnoreViewClickedNode(null);
        } else {
            // Enter touch mode once the user touches the screen.
            mInRotaryMode = false;
            if (sourceNode != null) {
                // Explicitly clear focus when user uses touch in another window.
                maybeClearFocusInCurrentWindow(sourceNode);

                if (!sourceNode.equals(mLastTouchedNode)) {
                    setLastTouchedNode(sourceNode);
                }
            }
        }
        Utils.recycleNode(sourceNode);
    }

    /** Handles {@link AccessibilityEvent#TYPE_VIEW_SCROLLED} event. */
    private void handleViewScrolledEvent(@NonNull AccessibilityEvent event) {
        if (mAfterScrollAction == AfterScrollAction.NONE
                || SystemClock.uptimeMillis() >= mAfterScrollActionUntil) {
            return;
        }
        AccessibilityNodeInfo sourceNode = event.getSource();
        if (sourceNode == null || !Utils.isScrollableContainer(sourceNode)) {
            Utils.recycleNode(sourceNode);
            return;
        }
        switch (mAfterScrollAction) {
            case FOCUS_PREVIOUS:
            case FOCUS_NEXT: {
                if (mFocusedNode.equals(sourceNode)) {
                    break;
                }
                AccessibilityNodeInfo target = Navigator.findFocusableDescendantInDirection(
                        sourceNode, mFocusedNode,
                        mAfterScrollAction == AfterScrollAction.FOCUS_PREVIOUS
                                ? View.FOCUS_BACKWARD
                                : View.FOCUS_FORWARD);
                if (target == null) {
                    break;
                }
                L.d("Focusing %s after scroll",
                        mAfterScrollAction == AfterScrollAction.FOCUS_PREVIOUS
                                ? "previous"
                                : "next");
                if (performFocusAction(target)) {
                    mAfterScrollAction = AfterScrollAction.NONE;
                }
                Utils.recycleNode(target);
                break;
            }
            case FOCUS_FIRST:
            case FOCUS_LAST: {
                AccessibilityNodeInfo target =
                        mAfterScrollAction == AfterScrollAction.FOCUS_FIRST
                                ? mNavigator.findFirstFocusableDescendant(sourceNode)
                                : mNavigator.findLastFocusableDescendant(sourceNode);
                if (target == null) {
                    break;
                }
                L.d("Focusing %s after scroll",
                        mAfterScrollAction == AfterScrollAction.FOCUS_FIRST ? "first" : "last");
                if (performFocusAction(target)) {
                    mAfterScrollAction = AfterScrollAction.NONE;
                }
                Utils.recycleNode(target);
                break;
            }
            default:
                throw new IllegalStateException(
                        "Unknown after scroll action: " + mAfterScrollAction);
        }
        Utils.recycleNode(sourceNode);
    }

    /** Handles {@link AccessibilityEvent#TYPE_WINDOWS_CHANGED} event. */
    private void handleWindowsChangedEvent(@NonNull AccessibilityEvent event) {
        if ((event.getWindowChanges() & WINDOWS_CHANGE_REMOVED) != 0
                && mInRotaryMode
                && mFocusedNode != null
                && mFocusedNode.getWindowId() == event.getWindowId()) {
            // The window containing the focused node is gone. Restore focus to the last
            // focused node in the last focused window.
            setFocusedNode(null);
            AccessibilityNodeInfo newFocus = mNavigator.getMostRecentFocus();
            if (newFocus != null) {
                performFocusAction(newFocus);
                newFocus.recycle();
            }
        }
    }

    private static int getKeyCode(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (Build.IS_DEBUGGABLE) {
            Integer mappingKeyCode = TEST_TO_REAL_KEYCODE_MAP.get(keyCode);
            if (mappingKeyCode != null) {
                keyCode = mappingKeyCode;
            }
        }
        return keyCode;
    }

    /** Handles controller center button event. */
    private void handleCenterButtonEvent(int action, boolean longClick) {
        if (!isValidAction(action)) {
            return;
        }
        if (initFocus()) {
            return;
        }
        // Case 1: the focus is in application window, inject KeyEvent.KEYCODE_DPAD_CENTER event and
        // the application will handle it.
        if (isInApplicationWindow(mFocusedNode)) {
            injectKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER, action);
            setIgnoreViewClickedNode(mFocusedNode);
            return;
        }
        // We're done with ACTION_DOWN event.
        if (action == KeyEvent.ACTION_DOWN) {
            return;
        }

        // Case 2: the focus is not in application window (e.g., in system window) and the focused
        // node supports direct manipulation, enter direct manipulation mode.
        if (DirectManipulationHelper.supportDirectManipulation(mFocusedNode)) {
            if (!mInDirectManipulationMode) {
                mInDirectManipulationMode = true;
                L.d("Enter direct manipulation mode because focused node is clicked.");
            }
            return;
        }

        // Case 3: the focus is not in application window and the focused node doesn't support
        // direct manipulation, perform click or long click on the focused node.
        boolean result = mFocusedNode.performAction(
                longClick
                ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                : AccessibilityNodeInfo.ACTION_CLICK);
        if (!result) {
            L.w("Failed to perform " + (longClick ? "ACTION_LONG_CLICK" : "ACTION_CLICK")
                    + " on " + mFocusedNode);
        }
        if (!longClick) {
            setIgnoreViewClickedNode(mFocusedNode);
        }
    }

    private void handleNudgeEvent(int direction, int action) {
        if (!isValidAction(action)) {
            return;
        }
        if (initFocus()) {
            return;
        }

        // If the focused node is in direct manipulation mode, manipulate it directly.
        if (mInDirectManipulationMode) {
            if (isInApplicationWindow(mFocusedNode)) {
                injectKeyEventForDirection(direction, action);
            } else {
                L.d("Ignore nudge events because we're in DM mode and the focus is not in"
                        + " application window");
            }
            return;
        }

        // We're done with ACTION_UP event.
        if (action == KeyEvent.ACTION_UP) {
            return;
        }

        // If the focused node is not in direct manipulation mode, move the focus.
        // TODO(b/152438801): sometimes getWindows() takes 10s after boot.
        List<AccessibilityWindowInfo> windows = getWindows();
        AccessibilityNodeInfo targetNode =
                mNavigator.findNudgeTarget(windows, mFocusedNode, direction);
        Utils.recycleWindows(windows);
        if (targetNode == null) {
            L.w("Failed to find nudge target");
            return;
        }

        // Android doesn't clear focus automatically when focus is set in another window.
        maybeClearFocusInCurrentWindow(targetNode);

        performFocusAction(targetNode);
        Utils.recycleNode(targetNode);
    }

    private void handleRotaryEvent(RotaryEvent rotaryEvent) {
        if (rotaryEvent.getInputType() != CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION) {
            return;
        }
        boolean clockwise = rotaryEvent.isClockwise();
        int count = rotaryEvent.getNumberOfClicks();
        // TODO(b/153195148): Use the first eventTime for now. We'll need to improve it later.
        long eventTime = rotaryEvent.getUptimeMillisForClick(0);
        handleRotateEvent(clockwise, count, eventTime);
    }

    private void handleRotateEvent(boolean clockwise, int count, long eventTime) {
        // Clear focus area history if configured to do so, but not when rotating in the HUN. The
        // HUN overlaps the application window so it's common for focus areas to overlap, causing
        // geometric searches to fail. History is essential here.
        if (mClearFocusAreaHistoryWhenRotating && !isFocusInHunWindow()) {
            mNavigator.clearFocusAreaHistory();
        }
        if (initFocus()) {
            return;
        }

        int rotationCount = getRotateAcceleration(count, eventTime);

        // If a scrollable container is focused, no focusable descendants are visible, so scroll the
        // container.
        AccessibilityNodeInfo.AccessibilityAction scrollAction =
                clockwise ? ACTION_SCROLL_FORWARD : ACTION_SCROLL_BACKWARD;
        if (mFocusedNode != null && Utils.isScrollableContainer(mFocusedNode)
                && mFocusedNode.getActionList().contains(scrollAction)) {
            injectScrollEvent(mFocusedNode, clockwise, rotationCount);
            return;
        }

        // If the focused node is in direct manipulation mode, manipulate it directly.
        if (mInDirectManipulationMode) {
            if (isInApplicationWindow(mFocusedNode)) {
                AccessibilityWindowInfo window = mFocusedNode.getWindow();
                if (window == null) {
                    L.w("Failed to get window of " + mFocusedNode);
                    return;
                }
                int displayId = window.getDisplayId();
                window.recycle();
                // TODO(b/155823126): Add config to let OEMs determine the mapping.
                injectMotionEvent(displayId, MotionEvent.AXIS_SCROLL,
                        clockwise ? rotationCount : -rotationCount);
            } else {
                performScrollAction(mFocusedNode, clockwise);
            }
            return;
        }

        // If the focused node is not in direct manipulation mode, move the focus. Skip over
        // mScrollableContainer; we don't want to navigate from a focusable descendant to the
        // scrollable container except as a side-effect of scrolling.
        int remainingRotationCount = rotationCount;
        int direction = clockwise ? View.FOCUS_FORWARD : View.FOCUS_BACKWARD;
        Navigator.FindRotateTargetResult result = mNavigator.findRotateTarget(mFocusedNode,
                /* skipNode= */ mScrollableContainer, direction, rotationCount);
        if (result != null) {
            if (performFocusAction(result.node)) {
                remainingRotationCount -= result.advancedCount;
            }
            Utils.recycleNode(result.node);
        } else {
            L.w("Failed to find rotate target");
        }

        // If navigation didn't consume all of rotationCount and the focused node either is a
        // scrollable container or is a descendant of one, scroll it. The former happens when no
        // focusable views are visible in the scrollable container. The latter happens when there
        // are focusable views but they're in the wrong direction. Inject a MotionEvent rather than
        // performing an action so that the application can control the amount it scrolls. Scrolling
        // is only supported in the application window because injected events always go to the
        // application window. We don't bother checking whether the scrollable container can
        // currently scroll because there's nothing else to do if it can't.
        if (remainingRotationCount > 0 && isInApplicationWindow(mFocusedNode)
                && mScrollableContainer != null) {
            injectScrollEvent(mScrollableContainer, clockwise, remainingRotationCount);
        }
    }

    /** Handles Back button event. */
    private void handleBackButtonEvent(int action) {
        if (!isValidAction(action)) {
            return;
        }

        // If the focus is in application window, inject Back button event and the application will
        // handle it. If the focus is not in application window, exit direct manipulation mode on
        // key up.
        if (isInApplicationWindow(mFocusedNode)) {
            injectKeyEvent(KeyEvent.KEYCODE_BACK, action);
        } else if (action == KeyEvent.ACTION_UP) {
            L.d("Exit direct manipulation mode on back button event");
            mInDirectManipulationMode = false;
        }
    }

    private void onForegroundAppChanged(CharSequence packageName) {
        if (TextUtils.equals(mForegroundApp, packageName)) {
            return;
        }
        mForegroundApp = packageName;
        if (mInDirectManipulationMode) {
            L.d("Exit direct manipulation mode because the foreground app has changed");
            mInDirectManipulationMode = false;
        }
    }

    private static boolean isValidAction(int action) {
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            L.w("Invalid action " + action);
            return false;
        }
        return true;
    }

    /** Performs scroll action on the given {@code targetNode} if it supports scroll action. */
    private static void performScrollAction(@NonNull AccessibilityNodeInfo targetNode,
            boolean clockwise) {
        // TODO(b/155823126): Add config to let OEMs determine the mapping.
        int actionToPerform = clockwise
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        int supportedActions = targetNode.getActions();
        if ((actionToPerform & supportedActions) == 0) {
            L.w("Node " + targetNode + " doesn't support action " + actionToPerform);
            return;
        }
        boolean result = targetNode.performAction(actionToPerform);
        if (!result) {
            L.w("Failed to perform action " + actionToPerform + " on " + targetNode);
        }
    }

    /** Returns whether the given {@code node} is in the application window. */
    private static boolean isInApplicationWindow(@NonNull AccessibilityNodeInfo node) {
        if (TREAT_APP_WINDOW_AS_SYSTEM_WINDOW) {
            return false;
        }
        AccessibilityWindowInfo window = node.getWindow();
        if (window == null) {
            L.w("Failed to get window of " + node);
            return false;
        }
        boolean result = window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION;
        Utils.recycleWindow(window);
        return result;
    }

    /** Returns whether {@link #mFocusedNode} is in the HUN window. */
    private boolean isFocusInHunWindow() {
        if (mFocusedNode == null) {
            return false;
        }
        AccessibilityWindowInfo window = mFocusedNode.getWindow();
        if (window == null) {
            L.w("Failed to get window of " + mFocusedNode);
            return false;
        }
        boolean result = mNavigator.isHunWindow(window);
        Utils.recycleWindow(window);
        return result;
    }

    private void updateDirectManipulationMode(AccessibilityEvent event, boolean enable) {
        if (!mInRotaryMode || !DirectManipulationHelper.isDirectManipulation(event)) {
            return;
        }
        if (enable) {
            mFocusedNode = Utils.refreshNode(mFocusedNode);
            if (mFocusedNode == null) {
                L.w("Failed to enter direct manipulation mode because mFocusedNode is no longer "
                        + "in view tree.");
                return;
            }
            if (!mFocusedNode.isFocused()) {
                L.w("Failed to enter direct manipulation mode because mFocusedNode is no longer "
                        + "focused.");
                return;
            }
        }
        if (mInDirectManipulationMode != enable) {
            // Toggle direct manipulation mode upon app's request.
            mInDirectManipulationMode = enable;
            L.d((enable ? "Enter" : "Exit") + " direct manipulation mode upon app's request");
        }
    }

    /**
     * Injects a {@link MotionEvent} to scroll {@code scrollableContainer} by {@code rotationCount}
     * steps. The direction depends on the value of {@code clockwise}. Sets
     * {@link #mAfterScrollAction} to move the focus once the scroll occurs, as follows:<ul>
     *     <li>If the user is spinning the rotary controller quickly, focuses the first or last
     *         focusable descendant so that the next rotation event will scroll immediately.
     *     <li>If the user is spinning slowly and there are no focusable descendants visible,
     *         focuses the first focusable descendant to scroll into view. This will be the last
     *         focusable descendant when scrolling up.
     *     <li>If the user is spinning slowly and there are focusable descendants visible, focuses
     *         the next or previous focusable descendant.
     * </ul>
     */
    private void injectScrollEvent(@NonNull AccessibilityNodeInfo scrollableContainer,
            boolean clockwise, int rotationCount) {
        // TODO(b/155823126): Add config to let OEMs determine the mappings.
        if (rotationCount > 1) {
            // Focus last when quickly scrolling down so the next event scrolls.
            mAfterScrollAction = clockwise
                    ? AfterScrollAction.FOCUS_LAST
                    : AfterScrollAction.FOCUS_FIRST;
        } else {
            if (Utils.isScrollableContainer(mFocusedNode)) {
                // Focus first when scrolling down while no focusable descendants are visible.
                mAfterScrollAction = clockwise
                        ? AfterScrollAction.FOCUS_FIRST
                        : AfterScrollAction.FOCUS_LAST;
            } else {
                // Focus next when scrolling down with a focused descendant.
                mAfterScrollAction = clockwise
                        ? AfterScrollAction.FOCUS_NEXT
                        : AfterScrollAction.FOCUS_PREVIOUS;
            }
        }
        mAfterScrollActionUntil = SystemClock.uptimeMillis() + mAfterScrollTimeoutMs;
        int axis = Utils.isHorizontallyScrollableContainer(scrollableContainer)
                ? MotionEvent.AXIS_HSCROLL
                : MotionEvent.AXIS_VSCROLL;
        AccessibilityWindowInfo window = scrollableContainer.getWindow();
        if (window == null) {
            L.w("Failed to get window of " + scrollableContainer);
            return;
        }
        int displayId = window.getDisplayId();
        window.recycle();
        injectMotionEvent(displayId, axis, clockwise ? -rotationCount : rotationCount);
    }

    private void injectMotionEvent(int displayId, int axis, int axisValue) {
        long upTime = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0; // Any integer value but -1 (INVALID_POINTER_ID) is fine.
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        // No need to set X,Y coordinates. We use a non-pointer source so the event will be routed
        // to the focused view.
        coords[0].setAxisValue(axis, axisValue);
        MotionEvent motionEvent = MotionEvent.obtain(/* downTime= */ upTime,
                /* eventTime= */ upTime,
                MotionEvent.ACTION_SCROLL,
                /* pointerCount= */ 1,
                properties,
                coords,
                /* metaState= */ 0,
                /* buttonState= */ 0,
                /* xPrecision= */ 1.0f,
                /* yPrecision= */ 1.0f,
                /* deviceId= */ 0,
                /* edgeFlags= */ 0,
                InputDevice.SOURCE_ROTARY_ENCODER,
                displayId,
                /* flags= */ 0);

        if (motionEvent != null) {
            mInputManager.injectInputEvent(motionEvent,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        } else {
            L.w("Unable to obtain MotionEvent");
        }
    }

    private boolean injectKeyEventForDirection(int direction, int action) {
        Integer keyCode = DIRECTION_TO_KEYCODE_MAP.get(direction);
        if (keyCode == null) {
            throw new IllegalArgumentException("direction must be one of "
                    + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
        }
        return injectKeyEvent(keyCode, action);
    }

    private boolean injectKeyEvent(int keyCode, int action) {
        long upTime = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(
                /* downTime= */ upTime, /* eventTime= */ upTime, action, keyCode, /* repeat= */ 0);
        return mInputManager.injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    /**
     * Updates {@link #mFocusedNode} and {@link #mLastTouchedNode} in case the {@link View}s
     * represented by them are no longer in the view tree.
     */
    private void refreshSavedNodes() {
        mFocusedNode = Utils.refreshNode(mFocusedNode);
        mLastTouchedNode = Utils.refreshNode(mLastTouchedNode);
        mScrollableContainer = Utils.refreshNode(mScrollableContainer);
        mPreviousFocusedNode = Utils.refreshNode(mPreviousFocusedNode);
    }

    /**
     * This method should be called when receiving an event from a rotary controller. It does the
     * following:<ol>
     *     <li>If {@link #mFocusedNode} isn't null and represents a view that still exists, does
     *         nothing. The event isn't consumed in this case. This is the normal case.
     *     <li>If {@link #mScrollableContainer} isn't null and represents a view that still exists,
     *         focuses it. The event isn't consumed in this case. This can happen when the user
     *         rotates quickly as they scroll into a section without any focusable views.
     *     <li>If {@link #mLastTouchedNode} isn't null and represents a view that still exists,
     *         focuses it. The event is consumed in this case. This happens when the user switches
     *         from touch to rotary.
     * </ol>
     *
     * @return whether the event was consumed by this method. When {@code false},
     *         {@link #mFocusedNode} is guaranteed to not be {@code null}.
     */
    private boolean initFocus() {
        refreshSavedNodes();
        mInRotaryMode = true;
        if (mFocusedNode != null) {
            return false;
        }
        if (mScrollableContainer != null) {
            if (performFocusAction(mScrollableContainer)) {
                return false;
            }
        }
        if (mLastTouchedNode != null) {
            if (focusLastTouchedNode()) {
                return true;
            }
        }
        focusFirstFocusDescendant();
        return true;
    }

    /** Clears the current rotary focus if {@code targetFocus} is in a different window. */
    private void maybeClearFocusInCurrentWindow(@NonNull AccessibilityNodeInfo targetFocus) {
        if (mFocusedNode == null || !mFocusedNode.isFocused()
                || mFocusedNode.getWindowId() == targetFocus.getWindowId()) {
            return;
        }
        if (clearFocusInCurrentWindow()) {
            setFocusedNode(null);
        }
    }

    /**
     * Clears the current rotary focus.
     * <p>
     * If we really clear focus in the current window, Android will re-focus a view in the current
     * window automatically, resulting in the current window and the target window being focused
     * simultaneously. To avoid that we don't really clear the focus. Instead, we "park" the focus
     * on a FocusParkingView in the current window. FocusParkingView is transparent no matter
     * whether it's focused or not, so it's invisible to the user.
     *
     * @return whether the FocusParkingView was focused successfully
     */
    private boolean clearFocusInCurrentWindow() {
        if (mFocusedNode == null) {
            L.e("Don't call clearFocusInCurrentWindow() when mFocusedNode is null");
            return false;
        }
        AccessibilityWindowInfo window = mFocusedNode.getWindow();
        if (window == null) {
            L.w("Failed to get window of " + mFocusedNode);
            return false;
        }
        AccessibilityNodeInfo focusParkingView = mNavigator.findFocusParkingView(window);
        if (focusParkingView == null) {
            L.e("No FocusParkingView in " + window);
            window.recycle();
            return false;
        }
        window.recycle();
        boolean result = focusParkingView.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (result) {
            if (mFocusParkingView != null) {
                L.e("mFocusParkingView should be null but is " + mFocusParkingView);
                Utils.recycleNode(mFocusParkingView);
            }
            mFocusParkingView = copyNode(focusParkingView);
        } else {
            L.w("Failed to perform ACTION_FOCUS on " + focusParkingView);
        }
        focusParkingView.recycle();
        return result;
    }

    /**
     * Focuses the last touched node, if any.
     *
     * @return {@code true} if {@link #mLastTouchedNode} isn't {@code null} and it was
     *         successfully focused
     */
    private boolean focusLastTouchedNode() {
        boolean lastTouchedNodeFocused = false;
        if (mLastTouchedNode != null) {
            lastTouchedNodeFocused = performFocusAction(mLastTouchedNode);
            if (mLastTouchedNode != null) {
                setLastTouchedNode(null);
            }
        }
        return lastTouchedNodeFocused;
    }

    /**
     * Focuses the first focus descendant (a node inside a focus area that can take focus) in the
     * currently active window, if any.
     */
    private void focusFirstFocusDescendant() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            L.e("rootNode of active window is null");
            return;
        }
        AccessibilityNodeInfo targetNode = mNavigator.findFirstFocusDescendant(rootNode);
        rootNode.recycle();
        if (targetNode == null) {
            L.w("Failed to find the first focus descendant");
            return;
        }
        performFocusAction(targetNode);
        targetNode.recycle();
    }

    /**
     * Sets {@link #mFocusedNode} to a copy of the given node, and clears {@link #mLastTouchedNode}.
     */
    private void setFocusedNode(@Nullable AccessibilityNodeInfo focusedNode) {
        setFocusedNodeInternal(focusedNode);
        if (mFocusedNode != null && mLastTouchedNode != null) {
            setLastTouchedNodeInternal(null);
        }
    }

    private void setFocusedNodeInternal(@Nullable AccessibilityNodeInfo focusedNode) {
        if ((mFocusedNode == null && focusedNode == null) ||
                (mFocusedNode != null && mFocusedNode.equals(focusedNode))) {
            L.d("Don't reset mFocusedNode since it stays the same: " + mFocusedNode);
            return;
        }
        if (mInDirectManipulationMode && focusedNode == null) {
            // Toggle off direct manipulation mode since there is no focused node.
            mInDirectManipulationMode = false;
            L.d("Exit direct manipulation mode since there is no focused node");
        }

        // Recycle mPreviousFocusedNode only when it's not the same with focusedNode.
        if (mPreviousFocusedNode != focusedNode) {
            Utils.recycleNode(mPreviousFocusedNode);
        } else {
            // TODO(b/159949186)
            L.e("mPreviousFocusedNode shouldn't be the same with focusedNode " + focusedNode);
        }

        mPreviousFocusedNode = mFocusedNode;
        mFocusedNode = copyNode(focusedNode);

        // Set mScrollableContainer to the scrollable container which contains mFocusedNode, if any.
        // Skip if mFocusedNode is a FocusParkingView. The FocusParkingView is focused when the
        // focus view is scrolled off the screen. We'll focus the scrollable container when we
        // receive the TYPE_VIEW_FOCUSED event in this case.
        if (mFocusedNode == null) {
            setScrollableContainer(null);
        } else if (!Utils.isFocusParkingView(mFocusedNode)) {
            setScrollableContainer(mNavigator.findScrollableContainer(mFocusedNode));
        }

        // Cache the focused node by focus area.
        if (mFocusedNode != null) {
            mNavigator.saveFocusedNode(mFocusedNode);
        }
    }

    private void setScrollableContainer(@Nullable AccessibilityNodeInfo scrollableContainer) {
        if ((mScrollableContainer == null && scrollableContainer == null)
                || (mScrollableContainer != null
                        && mScrollableContainer.equals(scrollableContainer))) {
            return;
        }

        Utils.recycleNode(mScrollableContainer);
        mScrollableContainer = copyNode(scrollableContainer);
    }

    /**
     * Sets {@link #mLastTouchedNode} to a copy of the given node, and clears {@link #mFocusedNode}.
     */
    private void setLastTouchedNode(@Nullable AccessibilityNodeInfo lastTouchedNode) {
        setLastTouchedNodeInternal(lastTouchedNode);
        if (mLastTouchedNode != null && mFocusedNode != null) {
            setFocusedNodeInternal(null);
        }
    }

    private void setLastTouchedNodeInternal(@Nullable AccessibilityNodeInfo lastTouchedNode) {
        if ((mLastTouchedNode == null && lastTouchedNode == null)
                || (mLastTouchedNode != null && mLastTouchedNode.equals(lastTouchedNode))) {
            L.d("Don't reset mLastTouchedNode since it stays the same: " + mLastTouchedNode);
            return;
        }

        Utils.recycleNode(mLastTouchedNode);
        mLastTouchedNode = copyNode(lastTouchedNode);
    }

    private void setIgnoreViewClickedNode(@Nullable AccessibilityNodeInfo node) {
        if (mIgnoreViewClickedNode != null) {
            mIgnoreViewClickedNode.recycle();
        }
        mIgnoreViewClickedNode = copyNode(node);
        if (node != null) {
            mIgnoreViewClickedUntil = SystemClock.uptimeMillis() + mIgnoreViewClickedMs;
        }
    }

    /**
     * Performs {@link AccessibilityNodeInfo#ACTION_FOCUS} on the given {@code targetNode}.
     *
     * @return true if {@code targetNode} was focused already or became focused after performing
     *         {@link AccessibilityNodeInfo#ACTION_FOCUS}
     */
    private boolean performFocusAction(@NonNull AccessibilityNodeInfo targetNode) {
        if (targetNode.equals(mFocusedNode)) {
            return true;
        }
        if (targetNode.isFocused()) {
            L.w("targetNode is already focused: " + targetNode);
            setFocusedNode(targetNode);
            return true;
        }
        boolean focusCleared = false;
        if (Utils.hasFocus(targetNode)){
            // One of targetNode's descendants is already focused, so we can't perform ACTION_FOCUS
            // on targetNode directly. The workaround is to clear the focus first (by focusing on
            // the FocusParkingView), then focus on targetNode.
            L.d("One of targetNode's descendants is already focused: " + targetNode);
            if (!clearFocusInCurrentWindow()) {
                return false;
            }
            focusCleared = true;
        }
        // Now we can perform ACTION_FOCUS on targetNode since it doesn't have focus, or its
        // descendant's focus has been cleared.
        boolean result = targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (!result) {
            L.w("Failed to perform ACTION_FOCUS on node " + targetNode);
            // Previously we cleared the focus of targetNode's descendant, which won't reset the
            // focused node to null. So we need to reset it manually.
            if (focusCleared) {
                setFocusedNode(null);
            }
            return false;
        }

        setFocusedNode(targetNode);
        return true;
    }

    /**
     * Returns the number of "ticks" to rotate for a single rotate event with the given detent
     * {@code count} at the given time. Uses and updates {@link #mLastRotateEventTime}. The result
     * will be one, two, or three times the given detent {@code count} depending on the interval
     * between the current event and the previous event and the detent {@code count}.
     *
     * @param count     the number of detents the user rotated
     * @param eventTime the {@link SystemClock#uptimeMillis} when the event occurred
     * @return the number of "ticks" to rotate
     */
    private int getRotateAcceleration(int count, long eventTime) {
        // count is 0 when testing key "C" or "V" is pressed.
        if (count <= 0) {
            count = 1;
        }
        int result = count;
        // TODO(b/153195148): This method can be improved once we've plumbed through the VHAL
        //  changes. We'll get timestamps for each detent.
        long delta = (eventTime - mLastRotateEventTime) / count;  // Assume constant speed.
        if (delta <= mRotationAcceleration3xMs) {
            result = count * 3;
        } else if (delta <= mRotationAcceleration2xMs) {
            result = count * 2;
        }
        mLastRotateEventTime = eventTime;
        return result;
    }

    private AccessibilityNodeInfo copyNode(@Nullable AccessibilityNodeInfo node) {
        return mNodeCopier.copy(node);
    }

    /**
     * Moves focus from the given {@code focusParkingView} to a view near the previously focused
     * view, which is chosen in the following order:
     * <ol>
     *   <li> the previously focused view ({@link #mPreviousFocusedNode}), if any
     *   <li> the default focus (app:defaultFocus) in the FocusArea that contains {@link
     *        #mFocusedNode}, if any
     *   <li> the first focusable view in the FocusArea that contains {@link #mFocusedNode}, if any,
     *        excluding any FocusParkingViews
     *   <li> the default focus in the window, if any, excluding any FocusParkingViews
     *   <li> the first focusable view in the window, if any, excluding any FocusParkingViews
     * </ol>
     */
    private void moveFocusToNearbyView(@NonNull AccessibilityNodeInfo focusParkingView) {
        mPreviousFocusedNode = Utils.refreshNode(mPreviousFocusedNode);
        if (mPreviousFocusedNode != null && performFocusAction(mPreviousFocusedNode)) {
            L.d("Move focus to the previously focused node");
            return;
        }
        // TODO(b/158797952): do 2,3.
        if (focusParkingView.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
            L.d("Move focus to the default focus in the window");
            return;
        }
        L.d("Try to focus on the first focusable view in the window");
        focusFirstFocusDescendant();
    }
}
