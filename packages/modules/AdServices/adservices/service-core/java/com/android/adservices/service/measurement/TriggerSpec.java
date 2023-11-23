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

package com.android.adservices.service.measurement;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A class wrapper for the trigger specification from the input argument during source registration
 */
public class TriggerSpec {
    private List<Integer> mTriggerData;
    private int mEventReportWindowsStart;
    private List<Long> mEventReportWindowsEnd;
    private SummaryOperatorType mSummaryWindowOperator;
    private List<Integer> mSummaryBucket;

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TriggerSpec)) {
            return false;
        }
        TriggerSpec t = (TriggerSpec) obj;
        return mTriggerData.equals(t.mTriggerData)
                && mEventReportWindowsStart == t.mEventReportWindowsStart
                && mEventReportWindowsEnd.equals(t.mEventReportWindowsEnd)
                && mSummaryWindowOperator == t.mSummaryWindowOperator
                && mSummaryBucket.equals(t.mSummaryBucket);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mTriggerData,
                mEventReportWindowsStart,
                mEventReportWindowsEnd,
                mSummaryWindowOperator,
                mSummaryBucket);
    }

    /** @return Trigger Data */
    public List<Integer> getTriggerData() {
        return mTriggerData;
    }

    /** @return Event Report Windows Start */
    public int getEventReportWindowsStart() {
        return mEventReportWindowsStart;
    }

    /** @return Event Report Windows End */
    public List<Long> getEventReportWindowsEnd() {
        return mEventReportWindowsEnd;
    }

    /** @return Summary Window Operator */
    public SummaryOperatorType getSummaryWindowOperator() {
        return mSummaryWindowOperator;
    }

    /** @return Summary Bucket */
    public List<Integer> getSummaryBucket() {
        return mSummaryBucket;
    }

    /**
     * Encode the parameter to JSON
     *
     * @return json object encode this class
     */
    public JSONObject encodeJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("trigger_data", new JSONArray(mTriggerData));
        JSONObject windows = new JSONObject();
        windows.put("start_time", mEventReportWindowsStart);
        windows.put("end_times", new JSONArray(mEventReportWindowsEnd));
        json.put("event_report_windows", windows);
        json.put("summary_window_operator", mSummaryWindowOperator.name().toLowerCase());
        json.put("summary_buckets", new JSONArray(mSummaryBucket));
        return json;
    }

    private static <T extends Comparable<T>> boolean isStrictIncreasing(List<T> list) {
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i).compareTo(list.get(i - 1)) <= 0) {
                return false;
            }
        }
        return true;
    }

    /** The choice of the summary operator with the reporting window */
    public enum SummaryOperatorType {
        COUNT,
        VALUE_SUM
    }

    private static ArrayList<Integer> getIntArrayFromJSON(JSONObject json, String key)
            throws JSONException {
        ArrayList<Integer> result = new ArrayList<>();
        JSONArray valueArray = json.getJSONArray(key);
        for (int i = 0; i < valueArray.length(); i++) {
            result.add(valueArray.getInt(i));
        }
        return result;
    }

    private void validateParameters() {
        if (!isStrictIncreasing(mEventReportWindowsEnd)) {
            throw new IllegalArgumentException(FieldsKey.EVENT_REPORT_WINDOWS + " not increasing");
        }
        if (!isStrictIncreasing(mSummaryBucket)) {
            throw new IllegalArgumentException(FieldsKey.SUMMARY_BUCKETS + " not increasing");
        }
        if (mEventReportWindowsStart < 0) {
            mEventReportWindowsStart = 0;
        }
    }

    /** */
    public static final class Builder {
        private final TriggerSpec mBuilding;

        public Builder(JSONObject jsonObject) throws JSONException {
            mBuilding = new TriggerSpec();
            mBuilding.mSummaryWindowOperator = SummaryOperatorType.COUNT;
            mBuilding.mEventReportWindowsStart = 0;
            mBuilding.mSummaryBucket = new ArrayList<>();
            mBuilding.mEventReportWindowsEnd = new ArrayList<>();
            this.setTriggerData(getIntArrayFromJSON(jsonObject, FieldsKey.TRIGGER_DATA));
            if (mBuilding.mTriggerData.size()
                    > PrivacyParams.getMaxFlexibleEventTriggerDataCardinality()) {
                throw new IllegalArgumentException(
                        "Trigger Data Cardinality Exceeds "
                                + PrivacyParams.getMaxFlexibleEventTriggerDataCardinality());
            }
            JSONObject jsonReportWindows = jsonObject.getJSONObject(FieldsKey.EVENT_REPORT_WINDOWS);
            if (!jsonReportWindows.isNull(FieldsKey.START_TIME)) {
                this.setEventReportWindowsStart(jsonReportWindows.getInt(FieldsKey.START_TIME));
            }

            JSONArray jsonArray = jsonReportWindows.getJSONArray(FieldsKey.END_TIME);
            if (jsonArray.length() > PrivacyParams.getMaxFlexibleEventReportingWindows()) {
                throw new IllegalArgumentException(
                        "Number of Reporting Windows Exceeds "
                                + PrivacyParams.getMaxFlexibleEventReportingWindows());
            }
            List<Long> data = new ArrayList<>();
            for (int i = 0; i < jsonArray.length(); i++) {
                data.add(jsonArray.getLong(i));
            }
            this.setEventReportWindowsEnd(data);

            if (!jsonObject.isNull(FieldsKey.SUMMARY_WINDOW_OPERATOR)) {
                try {
                    SummaryOperatorType op =
                            SummaryOperatorType.valueOf(
                                    jsonObject
                                            .getString(FieldsKey.SUMMARY_WINDOW_OPERATOR)
                                            .toUpperCase());
                    this.setSummaryWindowOperator(op);
                } catch (IllegalArgumentException e) {
                    // if a summary window operator is defined, but not in the pre-defined list, it
                    // will throw to exception.
                    throw new IllegalArgumentException(
                            FieldsKey.SUMMARY_WINDOW_OPERATOR + " invalid");
                }
            }
            this.setSummaryBucket(getIntArrayFromJSON(jsonObject, FieldsKey.SUMMARY_BUCKETS));
            mBuilding.validateParameters();
        }

        /** See {@link TriggerSpec#getTriggerData()} ()}. */
        public Builder setTriggerData(List<Integer> triggerData) {
            mBuilding.mTriggerData = triggerData;
            return this;
        }

        /** See {@link TriggerSpec#getEventReportWindowsStart()} ()}. */
        public Builder setEventReportWindowsStart(int eventReportWindowsStart) {
            mBuilding.mEventReportWindowsStart = eventReportWindowsStart;
            return this;
        }

        /** See {@link TriggerSpec#getEventReportWindowsEnd()} ()}. */
        public Builder setEventReportWindowsEnd(List<Long> eventReportWindowsEnd) {
            mBuilding.mEventReportWindowsEnd = eventReportWindowsEnd;
            return this;
        }

        /** See {@link TriggerSpec#getSummaryWindowOperator()} ()}. */
        public Builder setSummaryWindowOperator(SummaryOperatorType summaryWindowOperator) {
            mBuilding.mSummaryWindowOperator = summaryWindowOperator;
            return this;
        }

        /** See {@link TriggerSpec#getSummaryBucket()} ()}. */
        public Builder setSummaryBucket(List<Integer> summaryBucket) {
            mBuilding.mSummaryBucket = summaryBucket;
            return this;
        }

        /** Build the {@link TriggerSpec}. */
        public TriggerSpec build() {
            return mBuilding;
        }
    }

    private interface FieldsKey {
        String END_TIME = "end_times";
        String START_TIME = "start_time";
        String SUMMARY_WINDOW_OPERATOR = "summary_window_operator";
        String EVENT_REPORT_WINDOWS = "event_report_windows";
        String TRIGGER_DATA = "trigger_data";
        String SUMMARY_BUCKETS = "summary_buckets";
    }
}
