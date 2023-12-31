/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.media.cts;

import static org.junit.Assume.assumeFalse;

import android.media.MediaCodec;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;


/**
 * Holds state associated with a Surface used for MediaCodec encoder input.
 * <p>
 * The constructor takes a Surface obtained from MediaCodec.createInputSurface(), and uses that
 * to create an EGL window surface.  Calls to eglSwapBuffers() cause a frame of data to be sent
 * to the video encoder.
 */
public class InputSurface implements InputSurfaceInterface {
    private static final String TAG = "InputSurface";

    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig[] mConfigs = new EGLConfig[1];

    private boolean mReleaseSurface;
    private Surface mSurface;
    private int mWidth;
    private int mHeight;

    /**
     * Creates an InputSurface from a Surface.
     */
    public InputSurface(Surface surface, boolean releaseSurface, boolean useHighBitDepth) {
        if (surface == null) {
            throw new NullPointerException();
        }
        mSurface = surface;
        mReleaseSurface = releaseSurface;

        eglSetup(useHighBitDepth);
    }

    public InputSurface(Surface surface, boolean useHighBitDepth) {
        this(surface, true, useHighBitDepth);
    }

    /**
     * Creates an InputSurface from a Surface.
     */
    public InputSurface(Surface surface) {
        this(surface, true, false);
    }

    /**
     * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
     */
    private void eglSetup(boolean useHighBitDepth) {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null;
            throw new RuntimeException("unable to initialize EGL14");
        }

        // Configure EGL for recordable and OpenGL ES 2.0.  We want enough RGB bits
        // to minimize artifacts from possible YUV conversion.
        int eglColorSize = useHighBitDepth ? 10 : 8;
        int eglAlphaSize = useHighBitDepth ? 2 : 0;
        int recordable = useHighBitDepth ? 0 : 1;
        int[] configAttribList = {
                EGL14.EGL_RED_SIZE, eglColorSize,
                EGL14.EGL_GREEN_SIZE, eglColorSize,
                EGL14.EGL_BLUE_SIZE, eglColorSize,
                EGL14.EGL_ALPHA_SIZE, eglAlphaSize,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, recordable,
                EGL14.EGL_NONE
        };
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, configAttribList, 0, mConfigs, 0, mConfigs.length,
                numConfigs, 0) || numConfigs[0] == 0) {
            String message = "Unable to find EGL config supporting renderable-type:ES2 "
                    + "surface-type:pbuffer r:" + eglColorSize + " g:" + eglColorSize
                    + " b:" + eglColorSize + " a:" + eglAlphaSize + ".";
            // When eglChooseConfig fails for RGBA10102, skip high bit depth testing as it is not
            // mandatory for devices to support this configuration.
            assumeFalse(message + " Skipping the test for high bit depth case", useHighBitDepth);
            throw new RuntimeException(message);
        }

        // Configure context for OpenGL ES 2.0.
        int[] contextAttribList = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, mConfigs[0], EGL14.EGL_NO_CONTEXT,
                contextAttribList, 0);
        checkEglError("eglCreateContext");
        if (mEGLContext == null) {
            throw new RuntimeException("null context");
        }

        // Create a window surface, and attach it to the Surface we received.
        createEGLSurface();

        mWidth = getWidth();
        mHeight = getHeight();
    }

    @Override
    public void updateSize(int width, int height) {
        if (width != mWidth || height != mHeight) {
            Log.d(TAG, "re-create EGLSurface");
            releaseEGLSurface();
            createEGLSurface();
            mWidth = getWidth();
            mHeight = getHeight();
        }
    }

    private void createEGLSurface() {
        //EGLConfig[] configs = new EGLConfig[1];
        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };
        mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, mConfigs[0], mSurface,
                surfaceAttribs, 0);
        checkEglError("eglCreateWindowSurface");
        if (mEGLSurface == null) {
            throw new RuntimeException("surface was null");
        }
    }
    private void releaseEGLSurface() {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            mEGLSurface = EGL14.EGL_NO_SURFACE;
        }
    }
    /**
     * Discard all resources held by this class, notably the EGL context.  Also releases the
     * Surface that was passed to our constructor.
     */
    @Override
    public void release() {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(mEGLDisplay);
        }

        if (mReleaseSurface) {
            mSurface.release();
        }

        mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        mEGLContext = EGL14.EGL_NO_CONTEXT;
        mEGLSurface = EGL14.EGL_NO_SURFACE;

        mSurface = null;
    }

    /**
     * Makes our EGL context and surface current.
     */
    @Override
    public void makeCurrent() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    public void makeUnCurrent() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    /**
     * Calls eglSwapBuffers.  Use this to "publish" the current frame.
     */
    @Override
    public boolean swapBuffers() {
        return EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
    }

    /**
     * Returns the Surface that the MediaCodec receives buffers from.
     */
    public Surface getSurface() {
        return mSurface;
    }

    /**
     * Queries the surface's width.
     */
    public int getWidth() {
        int[] value = new int[1];
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_WIDTH, value, 0);
        return value[0];
    }

    /**
     * Queries the surface's height.
     */
    public int getHeight() {
        int[] value = new int[1];
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_HEIGHT, value, 0);
        return value[0];
    }

    /**
     * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
     */
    @Override
    public void setPresentationTime(long nsecs) {
        EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs);
    }

    /**
     * Checks for EGL errors.
     */
    private void checkEglError(String msg) {
        int error;
        if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
        }
    }

    @Override
    public void configure(MediaCodec codec) {
        codec.setInputSurface(mSurface);
    }

    @Override
    public void configure(NdkMediaCodec codec) {
        codec.setInputSurface(mSurface);
    }

    /**
     * Clears the surface to black.
     * <p>
     * Ported from https://github.com/google/grafika
     */
    public static void clearSurface(Surface surface) {
        // We need to do this with OpenGL ES (*not* Canvas -- the "software render" bits
        // are sticky).  We can't stay connected to the Surface after we're done because
        // that'd prevent the video encoder from attaching.
        //
        // If the Surface is resized to be larger, the new portions will be black, so
        // clearing to something other than black may look weird unless we do the clear
        // post-resize.
        InputSurface win = new InputSurface(surface, /* release */ false,
                /* useHighBitDepth */ false);
        win.makeCurrent();
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        win.swapBuffers();
        win.release();
    }
}
