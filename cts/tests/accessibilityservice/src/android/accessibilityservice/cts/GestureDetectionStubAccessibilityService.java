/**
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.accessibilityservice.cts;

import android.accessibility.cts.common.InstrumentedAccessibilityService;

import android.app.Instrumentation;
import android.view.accessibility.AccessibilityEvent;
import java.util.ArrayList;

/** Accessibility service stub, which will collect recognized gestures. */
public class GestureDetectionStubAccessibilityService extends InstrumentedAccessibilityService {
    private static final long GESTURE_RECOGNIZE_TIMEOUT_MS = 3000;
    private static final long EVENT_RECOGNIZE_TIMEOUT_MS = 3000;
    // Member variables
    private final Object mLock = new Object();
    private ArrayList<Integer> mCollectedGestures = new ArrayList();
    private ArrayList<Integer> mCollectedEvents = new ArrayList();

    public static GestureDetectionStubAccessibilityService enableSelf(
            Instrumentation instrumentation) {
        return InstrumentedAccessibilityService.enableService(
                instrumentation, GestureDetectionStubAccessibilityService.class);
    }

    @Override
    protected boolean onGesture(int gestureId) {
        synchronized (mCollectedGestures) {
            mCollectedGestures.add(gestureId);
            mCollectedGestures.notifyAll(); // Stop waiting for gesture.
        }
        return true;
    }

    public void clearGestures() {
        synchronized (mCollectedGestures) {
            mCollectedGestures.clear();
        }
    }

    public int getGesturesSize() {
        synchronized (mCollectedGestures) {
            return mCollectedGestures.size();
        }
    }

    public int getGesture(int index) {
        synchronized (mCollectedGestures) {
            return mCollectedGestures.get(index);
        }
    }

    /** Wait for onGesture() to collect next gesture. */
    public void waitUntilGesture() {
        synchronized (mCollectedGestures) {
            if (mCollectedGestures.size() > 0) {
                return;
            }
            try {
                mCollectedGestures.wait(GESTURE_RECOGNIZE_TIMEOUT_MS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        synchronized (mLock) {
            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_TOUCH_INTERACTION_END:
                    mCollectedEvents.add(event.getEventType());
                    mLock.notifyAll();
                    break;
                case AccessibilityEvent.TYPE_TOUCH_INTERACTION_START:
                case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START:
                case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END:
                    mCollectedEvents.add(event.getEventType());
            }
        }
        super.onAccessibilityEvent(event);
    }

    public void clearEvents() {
        synchronized (mLock) {
            mCollectedEvents.clear();
        }
    }

    public int getEventsSize() {
        synchronized (mLock) {
            return mCollectedEvents.size();
        }
    }

    public int getEvent(int index) {
        synchronized (mLock) {
            return mCollectedEvents.get(index);
        }
    }

    /** Wait for onAccessibilityEvent() to collect next gesture. */
    public void waitUntilEvent(int count) {
        synchronized (mLock) {
            if (mCollectedEvents.size() >= count) {
                return;
            }
            try {
                mLock.wait(EVENT_RECOGNIZE_TIMEOUT_MS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
