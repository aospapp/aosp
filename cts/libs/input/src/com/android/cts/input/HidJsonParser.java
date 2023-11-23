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

package com.android.cts.input;

import android.content.Context;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


/**
 * Parse json resource file that contains the test commands for HidDevice
 *
 * For files containing reports and input events, each entry should be in the following format:
 * <code>
 * {"name": "test case name",
 *  "reports": reports,
 *  "events": input_events
 * }
 * </code>
 *
 * {@code reports} - an array of strings that contain hex arrays.
 * {@code input_events} - an array of dicts in the following format:
 * <code>
 * {"action": "down|move|up", "axes": {"axis_x": x, "axis_y": y}, "keycode": "button_a"}
 * </code>
 * {@code "axes"} should only be defined for motion events, and {@code "keycode"} for key events.
 * Timestamps will not be checked.

 * Example:
 * <code>
 * [{ "name": "press button A",
 *    "reports": ["report1",
 *                "report2",
 *                "report3"
 *               ],
 *    "events": [{"action": "down", "axes": {"axis_y": 0.5, "axis_x": 0.1}},
 *               {"action": "move", "axes": {"axis_y": 0.0, "axis_x": 0.0}}
 *              ]
 *  },
 *  ... more tests like that
 * ]
 * </code>
 */
public class HidJsonParser {
    private static final String TAG = "JsonParser";

    private Context mContext;

    public HidJsonParser(Context context) {
        mContext = context;
    }

    /**
     * Convenience function to create JSONArray from resource.
     * The resource specified should contain JSON array as the top-level structure.
     *
     * @param resourceId The resourceId that contains the json data (typically inside R.raw)
     */
    private JSONArray getJsonArrayFromResource(int resourceId) {
        String data = readRawResource(resourceId);
        try {
            return new JSONArray(data);
        } catch (JSONException e) {
            throw new RuntimeException(
                    "Could not parse resource " + resourceId + ", received: " + data);
        }
    }

    /**
     * Convenience function to read in an entire file as a String.
     *
     * @param id resourceId of the file
     * @return contents of the raw resource file as a String
     */
    private String readRawResource(int id) {
        InputStream inputStream = mContext.getResources().openRawResource(id);
        try {
            return readFully(inputStream);
        } catch (IOException e) {
            throw new RuntimeException("Could not read resource id " + id);
        }
    }

    /**
     * Read register command from raw resource.
     *
     * @param resourceId the raw resource id that contains the command
     * @return the command to register device that can be passed to HidDevice constructor
     */
    public String readRegisterCommand(int resourceId) {
        return readRawResource(resourceId);
    }

    /**
     * Read entire input stream until no data remains.
     *
     * @param inputStream
     * @return content of the input stream
     * @throws IOException
     */
    private String readFully(InputStream inputStream) throws IOException {
        OutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read = inputStream.read(buffer);
        while (read >= 0) {
            baos.write(buffer, 0, read);
            read = inputStream.read(buffer);
        }
        return baos.toString();
    }

    /**
     * Extract the device id from the raw resource file. This is needed in order to register
     * a HidDevice.
     *
     * @param resourceId resorce file that contains the register command.
     * @return hid device id
     */
    public int readDeviceId(int resourceId) {
        try {
            JSONObject json = new JSONObject(readRawResource(resourceId));
            return json.getInt("id");
        } catch (JSONException e) {
            throw new RuntimeException("Could not read device id from resource " + resourceId);
        }
    }

    /**
     * Read json resource, and return a {@code List} of HidTestData, which contains
     * the name of each test, along with the HID reports and the expected input events.
     */
    public List<HidTestData> getTestData(int resourceId) {
        JSONArray json = getJsonArrayFromResource(resourceId);
        List<HidTestData> tests = new ArrayList<HidTestData>();
        for (int testCaseNumber = 0; testCaseNumber < json.length(); testCaseNumber++) {
            HidTestData testData = new HidTestData();

            try {
                JSONObject testcaseEntry = json.getJSONObject(testCaseNumber);
                testData.name = testcaseEntry.getString("name");
                JSONArray reports = testcaseEntry.getJSONArray("reports");

                for (int i = 0; i < reports.length(); i++) {
                    String report = reports.getString(i);
                    testData.reports.add(report);
                }

                JSONArray events = testcaseEntry.getJSONArray("events");
                for (int i = 0; i < events.length(); i++) {
                    JSONObject entry = events.getJSONObject(i);

                    InputEvent event = null;
                    if (entry.has("keycode")) {
                        event = parseKeyEvent(entry);
                    } else if (entry.has("axes")) {
                        event = parseMotionEvent(entry);
                    } else {
                        throw new RuntimeException(
                                "Input event is not specified correctly. Received: " + entry);
                    }
                    testData.events.add(event);
                }
                tests.add(testData);
            } catch (JSONException e) {
                throw new RuntimeException("Could not process entry " + testCaseNumber);
            }
        }
        return tests;
    }

    private KeyEvent parseKeyEvent(JSONObject entry) throws JSONException {
        int action = keyActionFromString(entry.getString("action"));
        int keyCode = KeyEvent.keyCodeFromString(entry.getString("keycode"));
        return new KeyEvent(action, keyCode);
    }

    private MotionEvent parseMotionEvent(JSONObject entry) throws JSONException {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_UNKNOWN;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();

        JSONObject axes = entry.getJSONObject("axes");
        Iterator<String> keys = axes.keys();
        while (keys.hasNext()) {
            String axis = keys.next();
            float value = (float) axes.getDouble(axis);
            coords[0].setAxisValue(MotionEvent.axisFromString(axis), value);
        }

        int action = motionActionFromString(entry.getString("action"));
        // Only care about axes and action here. Times are not checked
        MotionEvent event = MotionEvent.obtain(/* downTime */ 0, /* eventTime */ 0, action,
                /* pointercount */ 1, properties, coords, 0, 0, 0f, 0f,
                0, 0, InputDevice.SOURCE_JOYSTICK, 0);
        return event;
    }

    private int keyActionFromString(String action) {
        switch (action.toUpperCase()) {
            case "DOWN":
                return KeyEvent.ACTION_DOWN;
            case "UP":
                return KeyEvent.ACTION_UP;
        }
        throw new RuntimeException("Unknown action specified: " + action);
    }

    private int motionActionFromString(String action) {
        switch (action.toUpperCase()) {
            case "DOWN":
                return MotionEvent.ACTION_DOWN;
            case "MOVE":
                return MotionEvent.ACTION_MOVE;
            case "UP":
                return MotionEvent.ACTION_UP;
        }
        throw new RuntimeException("Unknown action specified: " + action);
    }
}
