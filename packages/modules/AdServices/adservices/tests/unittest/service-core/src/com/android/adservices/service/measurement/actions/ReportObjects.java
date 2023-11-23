/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.adservices.service.measurement.actions;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Report objects include destination, report time, and payload.
 */
public final class ReportObjects {
    public final List<JSONObject> mEventReportObjects;
    public final List<JSONObject> mAggregateReportObjects;
    public final List<JSONObject> mDebugEventReportObjects;
    public final List<JSONObject> mDebugAggregateReportObjects;
    public final List<JSONObject> mDebugReportObjects;

    public ReportObjects() {
        mEventReportObjects = new ArrayList<>();
        mAggregateReportObjects = new ArrayList<>();
        mDebugEventReportObjects = new ArrayList<>();
        mDebugAggregateReportObjects = new ArrayList<>();
        mDebugReportObjects = new ArrayList<>();
    }

    public ReportObjects(
            List<JSONObject> eventReportObjects,
            List<JSONObject> aggregateReportObjects,
            List<JSONObject> debugEventReportObjects,
            List<JSONObject> debugAggregateReportObjects,
            List<JSONObject> debugReportObjects) {
        mEventReportObjects = eventReportObjects;
        mAggregateReportObjects = aggregateReportObjects;
        mDebugEventReportObjects = debugEventReportObjects;
        mDebugAggregateReportObjects = debugAggregateReportObjects;
        mDebugReportObjects = debugReportObjects;
    }
}
