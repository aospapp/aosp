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

package android.server.wm.scvh;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EmbeddedSCVHService extends Service {
    private static final String TAG = "SCVHEmbeddedService";
    private SurfaceControlViewHost mVr;

    private Handler mHandler;

    private SlowView mSlowView;

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Return the interface
        return new AttachEmbeddedWindow();
    }

    public static class SlowView extends TextView {
        private long mDelayMs;

        public SlowView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            try {
                Thread.sleep(mDelayMs);
            } catch (InterruptedException e) {
            }
        }

        public void setDelay(long delayMs) {
            mDelayMs = delayMs;
        }
    }

    private class AttachEmbeddedWindow extends IAttachEmbeddedWindow.Stub {
        @Override
        public SurfaceControlViewHost.SurfacePackage attachEmbedded(IBinder hostToken, int width,
                int height, int displayId, long delayMs) {
            CountDownLatch countDownLatch = new CountDownLatch(1);
            mHandler.post(() -> {
                Context context = EmbeddedSCVHService.this;
                Display display = getApplicationContext().getSystemService(
                        DisplayManager.class).getDisplay(displayId);
                mVr = new SurfaceControlViewHost(context, display, hostToken);
                FrameLayout content = new FrameLayout(context);

                mSlowView = new SlowView(context);
                mSlowView.setDelay(delayMs);
                mSlowView.setBackgroundColor(Color.BLUE);
                mSlowView.setTextColor(Color.WHITE);
                content.addView(mSlowView);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                        TYPE_APPLICATION, 0, PixelFormat.OPAQUE);
                lp.setTitle("EmbeddedWindow");
                mVr.setView(content, lp);

                content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(@NonNull View v) {
                        // First frame isn't included in the sync so don't notify the host about the
                        // surface package until the first draw has completed.
                        Transaction transaction = new Transaction().addTransactionCommittedListener(
                                getMainExecutor(), countDownLatch::countDown);
                        v.getRootSurfaceControl().applyTransactionOnDraw(transaction);
                    }

                    @Override
                    public void onViewDetachedFromWindow(@NonNull View v) {
                    }
                });
            });
            try {
                countDownLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, "Failed to wait for timeout");
            }
            return mVr.getSurfacePackage();
        }

        @Override
        public void relayout(WindowManager.LayoutParams lp) {
            Runnable runnable = () -> {
                mSlowView.setText(lp.width + "x" + lp.height);
                mVr.relayout(lp);
            };

            if (Thread.currentThread() == mHandler.getLooper().getThread()) {
                runnable.run();
            } else {
                mHandler.post(runnable);
            }

        }

        @Override
        public void sendCrash() {
            mVr.getView().getViewTreeObserver().addOnPreDrawListener(() -> {
                throw new RuntimeException();
            });
        }
    }
}
