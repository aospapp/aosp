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
package com.android.car.bugreport;

/** Defines {@link MetaBugReport} statuses. */
public enum Status {
    // Bugreport is being written
    STATUS_WRITE_PENDING(0),

    // Writing bugreport failed. This is a final state.
    STATUS_WRITE_FAILED(1),

    // Bugreport is waiting to be uploaded, or is currently uploading.
    STATUS_UPLOAD_PENDING(2),

    // Bugreport uploaded successfully. This is a final state.
    STATUS_UPLOAD_SUCCESS(3),

    // Bugreport failed to upload. This is a final state.
    STATUS_UPLOAD_FAILED(4),

    // Bugreport is cancelled by user. This is a final state.
    STATUS_USER_CANCELLED(5),

    // Bugreport is pending user choice on whether to upload or copy.
    STATUS_PENDING_USER_ACTION(6),

    // Bugreport was moved successfully.
    STATUS_MOVE_SUCCESSFUL(7),

    // Bugreport move has failed. This is a final state.
    STATUS_MOVE_FAILED(8),

    // Bugreport is moving to USB drive.
    STATUS_MOVE_IN_PROGRESS(9),

    // Bugreport is expired. Associated file is deleted from the disk.
    // This is the final state.
    STATUS_EXPIRED(10),

    // Bugreport needs audio message.
    STATUS_AUDIO_PENDING(11),

    // Bugreport was uploaded before. New upload failed.
    // It may happen when bugreport was uploaded but because
    // of network or other issues bugreport status wasn't changed from
    // STATUS_UPLOAD_PENDING to STATUS_UPLOAD_SUCCESS.
    //
    // The bugreport zip files are stored in the device for some time in case the previous
    // upload failed. The files are cleaned-up in ExpireOldBugReportsJob.
    // This is the final state.
    STATUS_UPLOADED_BEFORE(12);

    private final int mValue;

    Status(int value) {
        mValue = value;
    }

    /** Returns integer value of the status. */
    public int getValue() {
        return mValue;
    }

    /** Generates human-readable string from a status value. */
    public static String toString(int value) {
        switch (value) {
            case 0:
                return "Write pending";
            case 1:
                return "Write failed";
            case 2:
                return "Uploading, or waiting for network";
            case 3:
                return "Upload successful";
            case 4:
                return "Upload failed";
            case 5:
                return "User cancelled";
            case 6:
                return "Pending user action";
            case 7:
                return "Move successful";
            case 8:
                return "Move failed";
            case 9:
                return "Move in progress";
            case 10:
                return "Expired";
            case 11:
                return "Audio message pending";
            default:
                break;
        }
        return "unknown";
    }
}
