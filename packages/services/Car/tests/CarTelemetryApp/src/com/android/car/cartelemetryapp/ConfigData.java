/*
 * Copyright (C) 2022 The Android Open Source Project.
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

package com.android.car.cartelemetryapp;

import android.car.telemetry.TelemetryProto.TelemetryError;
import android.os.PersistableBundle;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/** Class for keeping config data. */
public class ConfigData implements Serializable {
    public boolean selected = true;
    public int onReadyTimes = 0;
    public int sentBytes = 0;
    public int errorCount = 0;
    private String mName;
    private Deque<BundleHistory> mBundleHistory = new ArrayDeque<>();
    private Deque<ErrorHistory> mErrorHistory = new ArrayDeque<>();
    private static final int HISTORY_SIZE = 10;

    ConfigData(String name) {
        mName = name;
    }

    /*
    * Gets the config name.
    */
    public String getName() {
        return mName;
    }

    /*
    * Adds a bundle to the bundles history deque.
    *
    * Newest entry is added to the front while oldest are a the end.
    * Only a limited number of entries are kept.
    *
    * @param time timestamp of the bundle.
    * @param bundle the {@link PersistableBundle} to be saved as history.
    */
    public void addBundle(LocalDateTime time, PersistableBundle bundle) {
        if (mBundleHistory.size() >= HISTORY_SIZE) {
            // Remove oldest element
            mBundleHistory.pollLast();
        }
        mBundleHistory.addFirst(new BundleHistory(time, bundle));
    }

    /**
     * Clears all of configs history.
     */
    public void clearHistory() {
        onReadyTimes = 0;
        sentBytes = 0;
        errorCount = 0;
        mBundleHistory.clear();
        mErrorHistory.clear();
    }

    /*
    * Adds an error data to the bundles errors deque.
    *
    * Newest entry is added to the front while oldest are a the end.
    * Only a limited number of entries are kept.
    *
    * @param time timestamp of the error.
    * @param error the {@link TelemetryError} to be saved as history.
    */
    public void addError(LocalDateTime time, TelemetryError error) {
        if (mErrorHistory.size() >= HISTORY_SIZE) {
            // Remove oldest element
            mErrorHistory.pollLast();
        }
        mErrorHistory.addFirst(new ErrorHistory(time, error));
    }

    /*
    * Gets the string representation of the bundle history.
    *
    * @return string of the saved bundles history.
    */
    public String getBundleHistoryString() {
        StringBuilder sb = new StringBuilder();
        for (BundleHistory bh : mBundleHistory) {
            sb.append(bh.getString()).append("\n");
        }
        return sb.toString();
    }

    /*
    * Gets the string representation of the error history.
    *
    * @return string of the saved error history.
    */
    public String getErrorHistoryString() {
        StringBuilder sb = new StringBuilder();
        for (ErrorHistory eh : mErrorHistory) {
            sb.append(eh.getString()).append("\n");
        }
        return sb.toString();
    }

    private static class BundleHistory implements Serializable {
        private LocalDateTime mTime;
        private transient PersistableBundle mBundle;

        BundleHistory(LocalDateTime t, PersistableBundle b) {
            mTime = t;
            mBundle = b;
        }

        LocalDateTime getTime() {
            return mTime;
        }

        PersistableBundle getBundle() {
            return mBundle;
        }

        String getString() {
            StringBuilder sb = new StringBuilder()
                    .append(mTime.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")))
                    .append(":\n");
            for (String key : mBundle.keySet()) {
                sb.append("    ")
                    .append(key)
                    .append(": ")
                    .append(mBundle.get(key).toString())
                    .append("\n");
            }
            return sb.toString();
        }

        private void writeObject(ObjectOutputStream oos) throws IOException {
            oos.defaultWriteObject();
            mBundle.writeToStream(oos);
        }

        private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            ois.defaultReadObject();
            mBundle = PersistableBundle.readFromStream(ois);
        }
    }

    private static class ErrorHistory implements Serializable {
        private LocalDateTime mTime;
        private transient TelemetryError mError;

        ErrorHistory(LocalDateTime t, TelemetryError e) {
            mTime = t;
            mError = e;
        }

        LocalDateTime getTime() {
            return mTime;
        }

        TelemetryError getError() {
            return mError;
        }

        String getString() {
            StringBuilder sb = new StringBuilder()
                    .append(mTime.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")))
                    .append(":\n")
                    .append("    Error type: ")
                    .append(mError.getErrorType().name())
                    .append("\n")
                    .append("    Message: ")
                    .append(mError.getMessage());
            return sb.toString();
        }

        private void writeObject(ObjectOutputStream oos) throws IOException {
            oos.defaultWriteObject();
            mError.writeTo(oos);
        }

        private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            ois.defaultReadObject();
            mError = TelemetryError.parseFrom(ois);
        }
    }
}
