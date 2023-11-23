/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.webkit.cts;

import android.webkit.cts.R;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;

import com.android.compatibility.common.util.NullWebViewUtils;

public class WebViewCtsActivity extends Activity {
    private WebView mWebView;
    private Exception mInflationException;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.webview_layout);
            mWebView = (WebView) findViewById(R.id.web_page);
            mInflationException = null;
        } catch (Exception e) {
            NullWebViewUtils.determineIfWebViewAvailable(this, e);
            // If WebView is available, then the exception we just caught should be propagated.
            if (NullWebViewUtils.isWebViewAvailable()) {
                mInflationException = e;
           }
        }
    }

    public boolean isMultiprocessMode() {
        // Currently multiprocess is disabled on low RAM 32 bit devices.
        return
            Build.SUPPORTED_64_BIT_ABIS.length > 0 ||
            !getSystemService(ActivityManager.class).isLowRamDevice();
    }

    public WebView getWebView() {
        if (mInflationException != null) {
            throw new RuntimeException("Exception caught in onCreate", mInflationException);
        }
        return mWebView;
    }

    @Override
    public void onDestroy() {
        if (mWebView != null) {
            ViewParent parent =  mWebView.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(mWebView);
            }
            mWebView.destroy();
        }
        super.onDestroy();
    }
}
