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

package com.android.tv.settings.dialog.old;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.android.tv.settings.R;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Locale;

/**
 * Fragment that shows a link containing the ToS.
 */
public class TosWebViewFragment extends Fragment {

    private static final String TAG = "TosWebViewFragment";
    private static final boolean DEBUG = false;
    // TODO switch to pointing to proper TLD once there is a reliable way to
    // map geography to proper TLD, b/11032160
    private static final String GOOGLE_TOS_URL = "http://www.google.com/intl/%y_%z/policies/terms/";
    private static final String GOOGLE_PRIVACY_URL =
        "http://www.google.com/intl/%y_%z/policies/privacy/";

    private static final String SETTINGS_SECURE_TOS_URL = "canvas_tos_url";
    private static final String SETTINGS_SECURE_PRIVACY_URL = "canvas_privacy_url";

    private static final int SOURCE_URL = 1;
    private static final int SOURCE_STRING = 2;
    private static final int SOURCE_RESOURCE_ID = 3;

    private static final String ARGUMENT_SHOW = "show";
    private static final String ARGUMENT_CONTENT_STRING = "content_string";
    private static final String ARGUMENT_CONTENT_URL = "content_url";
    private static final String ARGUMENT_CONTENT_RESOURCE_ID = "content_resource_id";

    public static final int SHOW_TERMS_OF_SERVICE = 3;
    public static final int SHOW_PRIVACY_POLICY = 4;
    public static final int SHOW_ADDITIONAL_TERMS = 5;

    /**
     * Create instance and select page to display. The content is selected by the single parameter
     * and determined by a URL or resource string depending on the page to show.
     *
     * @param show One of SHOW_TERMS_OF_SERVICE, SHOW_PRIVACY_POLICY, SHOW_ADDITIONAL_TERMS
     */
    public static TosWebViewFragment newInstance(int show) {
        TosWebViewFragment f = new TosWebViewFragment();
        Bundle args = new Bundle();
        args.putInt(ARGUMENT_SHOW, show);
        f.setArguments(args);
        return f;
    }

    /**
     * Create instance and display page from URL.
     *
     * @param uri URL of page to show.
     */
    public static TosWebViewFragment newInstanceUrl(String pageUrl) {
        TosWebViewFragment f = new TosWebViewFragment();
        Bundle args = new Bundle();
        args.putString(ARGUMENT_CONTENT_URL, pageUrl);
        f.setArguments(args);
        return f;
    }

    /**
     * Create instance and display page from a string.
     *
     * @param page Text to show.
     */
    public static TosWebViewFragment newInstance(String page) {
        TosWebViewFragment f = new TosWebViewFragment();
        Bundle args = new Bundle();
        args.putString(ARGUMENT_CONTENT_STRING, page);
        f.setArguments(args);
        return f;
    }

    /**
     * Create instance and display page from resource id.
     *
     * @param resourceId id or string resourse to show.
     */
    public static TosWebViewFragment newInstance_resourceId(int resourceId) {
        TosWebViewFragment f = new TosWebViewFragment();
        Bundle args = new Bundle();
        args.putInt(ARGUMENT_CONTENT_RESOURCE_ID, resourceId);
        f.setArguments(args);
        return f;
    }

    private class MyChromeClient extends WebChromeClient {
        @Override
        public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture,
                Message resultMsg) {
            resultMsg.obj = mWebView;
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
            return true;
        }
    }

    private class MyWebViewClient extends WebViewClient {

        /**
         * Before we load any page, we check with our session object to see if
         * we should do any special handling at this point
         */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                String failingUrl) {
            Log.w(TAG, String.format(
                    "onReceivedError: errorCode %d, description: %s, url: %s", errorCode,
                    description, failingUrl));
            mIsLoading = false;
            // If this is the first attempted page load, this could be a
            // connectivity issue
            // We treat it all as server error, let ShowError decide if network
            // is still up
            onWebLoginError(errorCode, description);
            super.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        public void onTooManyRedirects(WebView view, Message cancelMsg, Message continueMsg) {
            Log.e(TAG, "onTooManyRedirects");
            // Users probably don't care about redirects, it's a server error to
            // them
            onWebLoginError(0, "");
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (DEBUG) {
                Log.d(TAG, "Loaded " + url);
            }
            view.requestFocus();
        }

        private void onWebLoginError(int errorCode, String description) {
            // TODO: Show error?
        }
    }

    private WebView mWebView;
    private String mSource;
    private int mSourceType;
    private boolean mIsLoading;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle icicle) {
        View content = inflater.inflate(R.layout.navigable_webview, null);

        if (icicle == null) {
            mWebView = (WebView) content.findViewById(R.id.webview);
            CookieSyncManager.createInstance(getActivity());
            mWebView.setWebViewClient(new MyWebViewClient());
            mWebView.setWebChromeClient(new MyChromeClient());
            WebSettings s = mWebView.getSettings();
            s.setNeedInitialFocus(true);
            s.setJavaScriptEnabled(true);
            s.setSupportMultipleWindows(false);
            s.setSaveFormData(false);
            s.setSavePassword(false);
            s.setAllowFileAccess(false);
            s.setDatabaseEnabled(false);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            s.setLoadsImagesAutomatically(true);
            s.setLightTouchEnabled(false);
            s.setNeedInitialFocus(false);
            s.setUseWideViewPort(true);
            s.setSupportZoom(false);
            mWebView.setMapTrackballToArrowKeys(false);

            int show = getArguments().getInt(ARGUMENT_SHOW, -1);
            switch (show) {
                case SHOW_TERMS_OF_SERVICE:
                    mSourceType = SOURCE_URL;
                    mSource = Settings.Secure.getString(getActivity().getContentResolver(),
                        SETTINGS_SECURE_TOS_URL);
                    if (mSource == null) {
                        mSource = GOOGLE_TOS_URL;
                    }
                    mSource = substitueArguments(mSource);
                    break;
                case SHOW_PRIVACY_POLICY:
                    mSourceType = SOURCE_URL;
                    mSource = Settings.Secure.getString(getActivity().getContentResolver(),
                        SETTINGS_SECURE_PRIVACY_URL);
                    if (mSource == null) {
                        mSource = GOOGLE_PRIVACY_URL;
                    }
                    mSource = substitueArguments(mSource);
                    break;
                case SHOW_ADDITIONAL_TERMS:
                    mSourceType = SOURCE_STRING;
                    mSource = readStringFromResource(getActivity(),
                        R.raw.additional_terms_of_service);
                    break;
                default:
                    // User is specifying a page to display, either by Url, resource id or string.
                    mSource = getArguments().getString(ARGUMENT_CONTENT_URL);
                    if (mSource != null) {
                        mSourceType = SOURCE_URL;
                        mSource = substitueArguments(mSource);
                    } else {
                        mSourceType = SOURCE_STRING;
                        mSource = getArguments().getString(ARGUMENT_CONTENT_STRING);
                        if (mSource == null) {
                            int resourceId = getArguments().getInt(ARGUMENT_CONTENT_RESOURCE_ID);
                            mSource = getResources().getString(resourceId);
                        }
                    }
                    break;
            }
        }
        return content;
    }

    /**
     * Determines the current activity mode and initializes ui appropriately.
     */
    @Override
    public void onResume() {
        super.onResume();
        mIsLoading = true;
        switch (mSourceType) {
            case SOURCE_URL:
                mWebView.loadUrl(mSource);
                break;
            case SOURCE_STRING:
                mWebView.loadData(mSource,"text/html", null);
                break;
        }
        mWebView.onResume();
    }

    /**
     * Stops page loading if it is in progress.
     */
    @Override
    public void onPause() {
        if (mIsLoading) {
            mWebView.stopLoading();
            mIsLoading = false;
        }
        mWebView.onPause();
        super.onPause();
    }

    private String substitueArguments(String url) {
        // Substitute locale if present in property
        if (url.contains("%m")) {
            try {
                Configuration config = new Configuration();
                Settings.System.getConfiguration(getActivity().getContentResolver(), config);
                if (config.mcc != 0) {
                    url = url.replace("%m", Integer.toString(config.mcc));
                } else {
                    // This will happen if the device doesn't have a SIM, in
                    // which case we just use the locale.
                    url = url.replace("%m", "%s");
                }
            } catch (Exception e) {
                // Intentionally left blank
            }
        }
        if (url.contains("%s")) {
            Locale locale = Locale.getDefault();
            String tmp = locale.getLanguage() + "_" + locale.getCountry().toLowerCase();
            url = url.replace("%s", tmp);
        }

        // %y is used to indicate a language variable to be replaced at runtime.
        if (url.contains("%y")) {
            Locale locale = Locale.getDefault();
            url = url.replace("%y", locale.getLanguage());
        }

        // %z is used to indicate the country. We use the value set by the
        // SIM. We choose not to use the operator/network country but that
        // which is set on SIM as they most likely procured the device there
        if (url.contains("%z")) {
            try {
                TelephonyManager telephony = (TelephonyManager) getActivity()
                        .getSystemService(Context.TELEPHONY_SERVICE);

                Configuration config = new Configuration();
                Settings.System.getConfiguration(getActivity().getContentResolver(), config);

                if (telephony != null && config.mcc != 0) {
                    String simCountryIso = telephony.getSimCountryIso();
                    if (TextUtils.isEmpty(simCountryIso)) {
                        simCountryIso = "us";
                    }
                    url = url.replace("%z", simCountryIso);
                } else {
                    // This will happen if the device doesn't have a SIM, in
                    // which case we just use the country with the default
                    // locale country
                    Locale locale = Locale.getDefault();
                    url = url.replace("%z", locale.getCountry().toLowerCase());
                }
            } catch (Exception e) {
                // Intentionally left blank
            }
        }
        return url;
    }

    private String readStringFromResource(Context context, int resourceId) {
        StringBuilder contents = new StringBuilder();
        String sep = System.lineSeparator();
        try {
            InputStream is = context.getResources().openRawResource(resourceId);
            BufferedReader input = new BufferedReader(new InputStreamReader(is), 1024*8);
            try {
                String line = null;
                while ((line = input.readLine()) != null) {
                    contents.append(line);
                    contents.append(sep);
                }
            } finally {
                input.close();
            }
        } catch (FileNotFoundException ex) {
            Log.e(TAG, "Couldn't find the file " + resourceId  + " " + ex);
            return null;
        } catch (IOException ex){
            Log.e(TAG, "Error reading file " + resourceId + " " + ex);
            return null;
        }
        return contents.toString();
    }
}
