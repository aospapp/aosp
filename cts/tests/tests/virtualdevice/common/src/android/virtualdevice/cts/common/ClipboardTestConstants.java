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

package android.virtualdevice.cts.common;

import android.app.Activity;

public class ClipboardTestConstants {
    // The test activity should attempt to write a ClipData to the clipboard, and then read the
    // clipboard contents and send them back.
    public static final String ACTION_SET_AND_GET_CLIP = "setAndGetClip";

    // The test activity should attempt to write a ClipData to the clipboard.
    public static final String ACTION_SET_CLIP = "setClip";

    // The test activity should attempt to read the clipboard contents and send them back.
    public static final String ACTION_GET_CLIP = "getClip";

    // The test activity should register a ClipboardManager.OnPrimaryClipChangedListener, wait until
    // it fires, and then read and send the clipboard contents back.
    public static final String ACTION_WAIT_FOR_CLIP = "waitForClip";


    // A result code sent to indicate that the test activity has finished registering a clipboard
    // change listener.
    public static final int RESULT_CODE_CLIP_LISTENER_READY = Activity.RESULT_FIRST_USER;

    // A result code sent to indicate that the test activity has been attached to a window
    public static final int RESULT_CODE_ATTACHED_TO_WINDOW = Activity.RESULT_FIRST_USER;


    // A boolean indicating whether a test activity should automatically call finish() after sending
    // its result via the ResultReceiver.
    public static final String EXTRA_FINISH_AFTER_SENDING_RESULT = "finishAfterSendingResult";

    // A boolean indicating whether a test activity should wait for focus before processing the
    // action in the Intent sent to it.
    public static final String EXTRA_WAIT_FOR_FOCUS = "waitForFocus";

    // A boolean which if true indicates the window should be marked as not focusable so that it
    // doesn't accidentally receive focus.
    public static final String EXTRA_NOT_FOCUSABLE = "notFocusable";

    // A boolean extra to request that the activity send RESULT_CODE_ATTACHED_TO_WINDOW as soon
    // as it is attached to a window.
    public static final String EXTRA_NOTIFY_WHEN_ATTACHED_TO_WINDOW = "notifyWhenAttachedToWindow";


    // A parecelable containing a ResultReceiver used to send a result back to the caller.
    public static final String EXTRA_RESULT_RECEIVER = "resultReceiver";

    // A parcelable containing ClipData to be written to the clipboard
    public static final String EXTRA_SET_CLIP_DATA = "setClipData";

    // A boolean used to return the result of hasPrimaryClip()
    public static final String EXTRA_HAS_CLIP = "hasClip";

    // A parcelable used to return the ClipData from getPrimaryClip().
    public static final String EXTRA_GET_CLIP_DATA = "getClipData";
}
