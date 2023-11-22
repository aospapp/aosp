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
package android.uirendering.cts.testinfrastructure;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * A simple View that uses a CanvasClient to draw its contents
 */
public class CanvasClientView extends View {
    private CanvasClient mCanvasClient;
    private int mWidth;
    private int mHeight;

    public CanvasClientView(Context context, CanvasClient canvasClient, int width, int height) {
        super(context);
        mCanvasClient = canvasClient;
        mWidth = width;
        mHeight = height;
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (ActivityTestBase.DEBUG) {
            String s = canvas.isHardwareAccelerated() ? "HARDWARE" : "SOFTWARE";
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setTextSize(20);
            canvas.drawText(s, 200, 200, paint);
        }
        if (mCanvasClient != null) {
            canvas.save();
            canvas.clipRect(0, 0, mWidth, mHeight);
            mCanvasClient.draw(canvas, mWidth, mHeight);
            canvas.restore();
        }
    }
}
