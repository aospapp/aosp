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

package com.android.ondevicepersonalization.services.display;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.ondevicepersonalization.RenderOutput;
import android.ondevicepersonalization.SlotResult;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceControlViewHost.SurfacePackage;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.android.ondevicepersonalization.services.OnDevicePersonalizationExecutors;
import com.android.ondevicepersonalization.services.data.vendor.OnDevicePersonalizationVendorDataDao;
import com.android.ondevicepersonalization.services.display.velocity.VelocityEngineFactory;
import com.android.ondevicepersonalization.services.util.PackageUtils;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.apache.velocity.Template;
import org.apache.velocity.app.VelocityEngine;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Helper class to display personalized content. */
public class DisplayHelper {
    private static final String TAG = "DisplayHelper";
    @NonNull private final Context mContext;

    public DisplayHelper(Context context) {
        mContext = context;
    }

    /** Generates an HTML string from the template data in RenderOutput. */
    @NonNull public String generateHtml(
            @NonNull RenderOutput renderContentResult,
            @NonNull String servicePackageName) {
        // If htmlContent is provided, do not render the template.
        String htmlContent = renderContentResult.getContent();
        if (null != htmlContent && !htmlContent.isEmpty()) {
            return htmlContent;
        }
        PersistableBundle templateParams = renderContentResult.getTemplateParams();
        String templateId = renderContentResult.getTemplateId();
        if (null == templateParams || null == templateId) {
            throw new IllegalArgumentException(
                    "Valid rendering output not provided for generateHtml");
        }
        try {
            byte[] templateBytes = OnDevicePersonalizationVendorDataDao.getInstance(
                    mContext,
                    servicePackageName,
                    PackageUtils.getCertDigest(mContext, servicePackageName))
                    .readSingleVendorDataRow(templateId);
            if (null == templateBytes) {
                throw new IllegalArgumentException(
                        "Provided templateId not found during generateHtml");
            }
            String templateContent = new String(templateBytes, StandardCharsets.UTF_8);
            // Move the template into a temp file to pass to Velocity.
            String templateFileName = createTempTemplateFile(templateContent, servicePackageName);
            VelocityEngine ve = VelocityEngineFactory.getVelocityEngine(mContext);
            Template template = ve.getTemplate(templateFileName);
            org.apache.velocity.context.Context ctx =
                    VelocityEngineFactory.createVelocityContext(templateParams);

            StringWriter writer = new StringWriter();
            template.merge(ctx, writer);
            return writer.toString();
        } catch (PackageManager.NameNotFoundException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String createTempTemplateFile(String templateContent, String templateId)
            throws IOException {
        File temp = File.createTempFile(templateId, ".vm", mContext.getCacheDir());
        try (PrintWriter out = new PrintWriter(temp)) {
            out.print(templateContent);
        }
        temp.deleteOnExit();
        return temp.getName();
    }

    /** Creates a webview and displays the provided HTML. */
    @NonNull public ListenableFuture<SurfacePackage> displayHtml(
            @NonNull String html, @NonNull SlotResult slotResult,
            @NonNull String servicePackageName,
            @NonNull IBinder hostToken, int displayId, int width, int height) {
        SettableFuture<SurfacePackage> result = SettableFuture.create();
        try {
            Log.d(TAG, "displayHtml");
            OnDevicePersonalizationExecutors.getHandler().post(() -> {
                createWebView(html, slotResult, servicePackageName,
                        hostToken, displayId, width, height, result);
            });
        } catch (Exception e) {
            result.setException(e);
        }
        return result;
    }

    private void createWebView(
            @NonNull String html, @NonNull SlotResult slotResult,
            @NonNull String servicePackageName,
            @NonNull IBinder hostToken, int displayId, int width, int height,
            @NonNull SettableFuture<SurfacePackage> resultFuture) {
        try {
            Log.d(TAG, "createWebView() started");
            WebView webView = new WebView(mContext);
            webView.setWebViewClient(
                    new OdpWebViewClient(mContext, servicePackageName, slotResult));
            WebSettings webViewSettings = webView.getSettings();
            // Do not allow using file:// or content:// URLs.
            webViewSettings.setAllowFileAccess(false);
            webViewSettings.setAllowContentAccess(false);
            webView.loadData(html, "text/html; charset=utf-8", "UTF-8");

            Display display = mContext.getSystemService(DisplayManager.class).getDisplay(displayId);
            SurfaceControlViewHost host = new SurfaceControlViewHost(mContext, display, hostToken);
            host.setView(webView, width, height);
            SurfacePackage surfacePackage = host.getSurfacePackage();
            Log.d(TAG, "createWebView success: " + surfacePackage);
            resultFuture.set(surfacePackage);
        } catch (Exception e) {
            Log.d(TAG, "createWebView failed", e);
            resultFuture.setException(e);
        }
    }
}
