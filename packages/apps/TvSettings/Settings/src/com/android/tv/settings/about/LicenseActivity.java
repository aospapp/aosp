/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.tv.settings.about;

import com.android.tv.settings.R;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.zip.GZIPInputStream;

/**
 * Displays open source NOTICE files.
 */
public class LicenseActivity extends Activity {

    private static final String TAG = "LicenseActivity";
    private static final boolean LOGV = false;

    private static final String DEFAULT_LICENSE_PATH = "/system/etc/NOTICE.html.gz";
    private static final String PROPERTY_LICENSE_PATH = "ro.config.license_path";

    private WebView mWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.license_activity);
        mWebView = (WebView) findViewById(R.id.license);

        String fileName = SystemProperties.get(PROPERTY_LICENSE_PATH, DEFAULT_LICENSE_PATH);
        if (TextUtils.isEmpty(fileName)) {
            Log.e(TAG, "The system property for the license file is empty.");
            showErrorAndFinish();
            return;
        }

        new LicenseFileLoader().execute(fileName);
    }

    private class LicenseFileLoader extends AsyncTask<String, Void, String> {

        public static final int STATUS_OK = 0;
        public static final int STATUS_NOT_FOUND = 1;
        public static final int STATUS_READ_ERROR = 2;
        public static final int STATUS_EMPTY_FILE = 3;

        @Override
        protected void onPreExecute() {
            // TODO: implement loading text or graphic here.
            mWebView.setVisibility(View.GONE);
        }

        @Override
        protected String doInBackground(String... params) {
            int status = STATUS_OK;
            String fileName = params[0];

            InputStreamReader inputReader = null;
            StringBuilder data = new StringBuilder(2048);
            try {
                char[] tmp = new char[2048];
                int numRead;
                if (fileName.endsWith(".gz")) {
                    inputReader = new InputStreamReader(
                        new GZIPInputStream(new FileInputStream(fileName)));
                } else {
                    inputReader = new FileReader(fileName);
                }

                while ((numRead = inputReader.read(tmp)) >= 0) {
                    data.append(tmp, 0, numRead);
                }
            } catch (FileNotFoundException e) {
                Log.e(TAG, "License HTML file not found at " + fileName, e);
                status = STATUS_NOT_FOUND;
            } catch (IOException e) {
                Log.e(TAG, "Error reading license HTML file at " + fileName, e);
                status = STATUS_READ_ERROR;
            } finally {
                try {
                    if (inputReader != null) {
                        inputReader.close();
                    }
                } catch (IOException e) {
                }
            }

            if ((status == STATUS_OK) && TextUtils.isEmpty(data)) {
                Log.e(TAG, "License HTML is empty (from " + fileName + ")");
                status = STATUS_EMPTY_FILE;
            }
            return data.toString();
        }

        @Override
        protected void onPostExecute(String result) {
            showDataInPage(result);
        }
    }

    private void showDataInPage(String data) {
        // TODO: implement hiding loading text or graphic here.
        mWebView.setVisibility(View.VISIBLE);
        mWebView.loadDataWithBaseURL(null, data, "text/html", "utf-8", null);
    }

    private void showErrorAndFinish() {
        Toast.makeText(this, R.string.about_license_activity_unavailable, Toast.LENGTH_LONG)
                .show();
        finish();
    }
}
