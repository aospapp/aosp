/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.car.kitchensink.property;

import static java.lang.Integer.toHexString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.VehiclePropertyIds;
import android.car.VehiclePropertyType;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.GetPropertyCallback;
import android.car.hardware.property.CarPropertyManager.GetPropertyRequest;
import android.car.hardware.property.CarPropertyManager.GetPropertyResult;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class PropertyTestFragment extends Fragment implements OnItemSelectedListener {
    private static final String TAG = "PropertyTestFragment";

    private KitchenSinkActivity mActivity;
    private CarPropertyManager mMgr;
    private List<PropertyInfo> mPropInfo = null;
    private Spinner mAreaId;
    private TextView mEventLog;
    private TextView mGetValue;
    private ListView mListView;
    private Spinner mPropertyId;
    private ScrollView mScrollView;
    private EditText mSetValue;
    private GetPropertyCallback mGetPropertyCallback = new GetPropertyCallback() {
        @Override
        public void onSuccess(@NonNull GetPropertyResult<?> getPropertyResult) {
            int propId = getPropertyResult.getPropertyId();
            long timestamp = getPropertyResult.getTimestampNanos();
            setTextOnSuccess(propId, timestamp, getPropertyResult.getValue());
        }

        @Override
        public void onFailure(@NonNull CarPropertyManager.PropertyAsyncError propertyAsyncError) {
            Log.e(TAG, "Failed to get async VHAL property");
            Toast.makeText(mActivity, "Failed to get async VHAL property with error code: "
                    + propertyAsyncError.getErrorCode() + " and vendor error code: "
                    + propertyAsyncError.getVendorErrorCode(), Toast.LENGTH_SHORT).show();
        }
    };

    private CarPropertyManager.SetPropertyCallback mSetPropertyCallback =
            new CarPropertyManager.SetPropertyCallback() {
        @Override
        public void onSuccess(@NonNull CarPropertyManager.SetPropertyResult setPropertyResult) {
            Toast.makeText(mActivity, "Success", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onFailure(@NonNull CarPropertyManager.PropertyAsyncError propertyAsyncError) {
            Log.e(TAG, "Failed to get async VHAL property");
            Toast.makeText(mActivity, "Failed to set async VHAL property with error code: "
                    + propertyAsyncError.getErrorCode() + " and vendor error code: "
                    + propertyAsyncError.getVendorErrorCode(), Toast.LENGTH_SHORT).show();
        }
    };

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.property, container, false);
        // Get resource IDs
        mAreaId = view.findViewById(R.id.sAreaId);
        mEventLog = view.findViewById(R.id.tvEventLog);
        mGetValue = view.findViewById(R.id.tvGetPropertyValue);
        mListView = view.findViewById(R.id.lvPropertyList);
        mPropertyId = view.findViewById(R.id.sPropertyId);
        mScrollView = view.findViewById(R.id.svEventLog);
        mSetValue = view.findViewById(R.id.etSetPropertyValue);
        mActivity = (KitchenSinkActivity) getActivity();

        final Runnable r = () -> {
            mMgr = mActivity.getPropertyManager();
            populateConfigList();
            mListView.setAdapter(new PropertyListAdapter(mPropInfo, mMgr, mEventLog, mScrollView,
                    mActivity));

            // Configure dropdown menu for propertyId spinner
            ArrayAdapter<PropertyInfo> adapter =
                    new ArrayAdapter<PropertyInfo>(mActivity, android.R.layout.simple_spinner_item,
                            mPropInfo);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mPropertyId.setAdapter(adapter);
            mPropertyId.setOnItemSelectedListener(this);
        };
        mActivity.requestRefreshManager(r, new Handler(getContext().getMainLooper()));

        // Configure listeners for buttons
        Button b = view.findViewById(R.id.bGetProperty);
        b.setOnClickListener(v -> {
            try {
                PropertyInfo info = (PropertyInfo) mPropertyId.getSelectedItem();
                int propId = info.mConfig.getPropertyId();
                int areaId = Integer.decode(mAreaId.getSelectedItem().toString());
                CarPropertyValue value = mMgr.getProperty(propId, areaId);
                setTextOnSuccess(propId, value.getTimestamp(), value.getValue());
            } catch (Exception e) {
                Log.e(TAG, "Failed to get VHAL property", e);
                Toast.makeText(mActivity, "Failed to get VHAL property: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });

        b = view.findViewById(R.id.getPropertyAsync);
        b.setOnClickListener(v -> {
            try {
                PropertyInfo info = (PropertyInfo) mPropertyId.getSelectedItem();
                int propId = info.mConfig.getPropertyId();
                int areaId = Integer.decode(mAreaId.getSelectedItem().toString());
                GetPropertyRequest getPropertyRequest = mMgr.generateGetPropertyRequest(propId,
                        areaId);
                mMgr.getPropertiesAsync(List.of(getPropertyRequest),
                        /* cancellationSignal= */ null, /* callbackExecutor= */ null,
                        mGetPropertyCallback);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get async VHAL property", e);
                Toast.makeText(mActivity, "Failed to get async VHAL property: "
                                + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        b = view.findViewById(R.id.bSetProperty);
        b.setOnClickListener(v -> {
            try {
                PropertyInfo info = (PropertyInfo) mPropertyId.getSelectedItem();
                int propId = info.mConfig.getPropertyId();
                int areaId = Integer.decode(mAreaId.getSelectedItem().toString());
                String valueString = mSetValue.getText().toString();

                switch (propId & VehiclePropertyType.MASK) {
                    case VehiclePropertyType.BOOLEAN:
                        Boolean boolVal = Boolean.parseBoolean(valueString);
                        mMgr.setBooleanProperty(propId, areaId, boolVal);
                        break;
                    case VehiclePropertyType.FLOAT:
                        Float floatVal = Float.parseFloat(valueString);
                        mMgr.setFloatProperty(propId, areaId, floatVal);
                        break;
                    case VehiclePropertyType.INT32:
                        Integer intVal = Integer.parseInt(valueString);
                        mMgr.setIntProperty(propId, areaId, intVal);
                        break;
                    default:
                        Toast.makeText(mActivity, "PropertyType=0x" + toHexString(propId
                                        & VehiclePropertyType.MASK) + " is not handled!",
                                Toast.LENGTH_LONG).show();
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to set VHAL property", e);
                Toast.makeText(mActivity, "Failed to set VHAL property: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });

        b = view.findViewById(R.id.SetPropertyAsync);
        b.setOnClickListener(v -> {
            try {
                PropertyInfo info = (PropertyInfo) mPropertyId.getSelectedItem();
                int propId = info.mConfig.getPropertyId();
                int areaId = Integer.decode(mAreaId.getSelectedItem().toString());
                String valueString = mSetValue.getText().toString();

                switch (propId & VehiclePropertyType.MASK) {
                    case VehiclePropertyType.BOOLEAN:
                        Boolean boolVal = Boolean.parseBoolean(valueString);
                        callSetPropertiesAsync(propId, areaId, boolVal);
                        break;
                    case VehiclePropertyType.FLOAT:
                        Float floatVal = Float.parseFloat(valueString);
                        callSetPropertiesAsync(propId, areaId, floatVal);
                        break;
                    case VehiclePropertyType.INT32:
                        Integer intVal = Integer.parseInt(valueString);
                        callSetPropertiesAsync(propId, areaId, intVal);
                        break;
                    default:
                        Toast.makeText(mActivity, "PropertyType=0x" + toHexString(propId
                                        & VehiclePropertyType.MASK) + " is not handled!",
                                Toast.LENGTH_LONG).show();
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to set VHAL property", e);
                Toast.makeText(mActivity, "Failed to set async VHAL property: "
                        + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        b = view.findViewById(R.id.bClearLog);
        b.setOnClickListener(v -> {
            mEventLog.setText("");
        });

        return view;
    }

    private void populateConfigList() {
        try {
            mPropInfo = mMgr.getPropertyList()
                    .stream()
                    .map(PropertyInfo::new)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            Log.e(TAG, "Unhandled exception in populateConfigList: ", e);
        }
    }

    // Spinner callbacks
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        PropertyInfo info = (PropertyInfo) parent.getItemAtPosition(pos);
        int[] areaIds = info.mConfig.getAreaIds();
        List<String> areaString = new LinkedList<String>();
        if (areaIds.length == 0) {
            areaString.add("0x0");
        } else {
            for (int areaId : areaIds) {
                areaString.add("0x" + toHexString(areaId));
            }
        }

        // Configure dropdown menu for propertyId spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(mActivity,
                android.R.layout.simple_spinner_item, areaString);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAreaId.setAdapter(adapter);
    }

    public void onNothingSelected(AdapterView<?> parent) {
        // Another interface callback
    }

    private void setTextOnSuccess(int propId, long timestamp, Object value) {
        if (propId == VehiclePropertyIds.WHEEL_TICK) {
            Object[] ticks = (Object[]) value;
            mGetValue.setText("Timestamp=" + timestamp
                    + "\n[0]=" + (Long) ticks[0]
                    + "\n[1]=" + (Long) ticks[1] + " [2]=" + (Long) ticks[2]
                    + "\n[3]=" + (Long) ticks[3] + " [4]=" + (Long) ticks[4]);
        } else {
            mGetValue.setText("Timestamp=" + timestamp
                    + "\nvalue=" + value
                    + "\nread=" + mMgr.getReadPermission(propId)
                    + "\nwrite=" + mMgr.getWritePermission(propId));
        }
    }

    private <T> void callSetPropertiesAsync(int propId, int areaId, T request) {
        mMgr.setPropertiesAsync(
                List.of(mMgr.generateSetPropertyRequest(propId, areaId, request)),
                /* cancellationSignal= */ null,
                /* callbackExecutor= */ null, mSetPropertyCallback);
    }
}
