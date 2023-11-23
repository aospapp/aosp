// Copyright (C) 2022 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "DisplaySurfaceGl.h"

#include "OpenGLESDispatch/DispatchTables.h"
#include "OpenGLESDispatch/EGLDispatch.h"
#include "host-common/GfxstreamFatalError.h"
#include "host-common/logging.h"

namespace gfxstream {
namespace gl {
namespace {

using emugl::ABORT_REASON_OTHER;
using emugl::FatalError;

class DisplaySurfaceGlContextHelper : public ContextHelper {
  public:
    DisplaySurfaceGlContextHelper(EGLDisplay display,
                                  EGLSurface surface,
                                  EGLContext context)
        : mDisplay(display),
          mSurface(surface),
          mContext(context) {
        if (mDisplay == EGL_NO_DISPLAY) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << "DisplaySurfaceGlContextHelper created with no display?";
        }
        if (mSurface == EGL_NO_SURFACE) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << "DisplaySurfaceGlContextHelper created with no surface?";
        }
        if (mContext == EGL_NO_CONTEXT) {
            GFXSTREAM_ABORT(FatalError(ABORT_REASON_OTHER))
                << "DisplaySurfaceGlContextHelper created with no context?";
        }
    }

    bool setupContext() override {
        EGLContext currentContext = s_egl.eglGetCurrentContext();
        EGLSurface currentDrawSurface = s_egl.eglGetCurrentSurface(EGL_DRAW);
        EGLSurface currentReadSurface = s_egl.eglGetCurrentSurface(EGL_READ);

        if (currentContext != mContext ||
            currentDrawSurface != mSurface ||
            currentReadSurface != mSurface) {
            if (!s_egl.eglMakeCurrent(mDisplay, mSurface, mSurface, mContext)) {
                ERR("Failed to make display surface context current: %d", s_egl.eglGetError());
                return false;
            }
        }

        mPreviousContext = currentContext;
        mPreviousDrawSurface = currentDrawSurface;
        mPreviousReadSurface = currentReadSurface;

        mIsBound = true;

        return mIsBound;
    }

    void teardownContext() override {
        EGLContext currentContext = s_egl.eglGetCurrentContext();
        EGLSurface currentDrawSurface = s_egl.eglGetCurrentSurface(EGL_DRAW);
        EGLSurface currentReadSurface = s_egl.eglGetCurrentSurface(EGL_READ);

        if (currentContext != mPreviousContext ||
            currentDrawSurface != mPreviousDrawSurface ||
            currentReadSurface != mPreviousReadSurface) {
            if (!s_egl.eglMakeCurrent(mDisplay,
                                      mPreviousDrawSurface,
                                      mPreviousReadSurface,
                                      mPreviousContext)) {
                ERR("Failed to make restore previous context: %d", s_egl.eglGetError());
                return;
            }
        }

        mPreviousContext = EGL_NO_CONTEXT;
        mPreviousDrawSurface = EGL_NO_SURFACE;
        mPreviousReadSurface = EGL_NO_SURFACE;
        mIsBound = false;
    }

    bool isBound() const override { return mIsBound; }

  private:
    EGLDisplay mDisplay = EGL_NO_DISPLAY;
    EGLSurface mSurface = EGL_NO_SURFACE;
    EGLContext mContext = EGL_NO_CONTEXT;

    EGLContext mPreviousContext = EGL_NO_CONTEXT;
    EGLSurface mPreviousReadSurface = EGL_NO_SURFACE;
    EGLSurface mPreviousDrawSurface = EGL_NO_SURFACE;

    bool mIsBound = false;
};

}  // namespace

/*static*/
std::unique_ptr<DisplaySurfaceGl> DisplaySurfaceGl::createPbufferSurface(
        EGLDisplay display,
        EGLConfig config,
        EGLContext shareContext,
        const EGLint* contextAttribs,
        EGLint width,
        EGLint height) {
    EGLContext context = s_egl.eglCreateContext(display, config, shareContext, contextAttribs);
    if (context == EGL_NO_CONTEXT) {
        ERR("Failed to create context for DisplaySurfaceGl.");
        return nullptr;
    }

    const EGLint surfaceAttribs[] = {
        EGL_WIDTH, width,   //
        EGL_HEIGHT, height, //
        EGL_NONE,           //
    };
    EGLSurface surface = s_egl.eglCreatePbufferSurface(display, config, surfaceAttribs);
    if (surface == EGL_NO_SURFACE) {
        ERR("Failed to create pbuffer surface for DisplaySurfaceGl.");
        return nullptr;
    }

    return std::unique_ptr<DisplaySurfaceGl>(new DisplaySurfaceGl(display, surface, context));
}

/*static*/
std::unique_ptr<DisplaySurfaceGl> DisplaySurfaceGl::createWindowSurface(
        EGLDisplay display,
        EGLConfig config,
        EGLContext shareContext,
        const GLint* contextAttribs,
        FBNativeWindowType window) {
    EGLContext context = s_egl.eglCreateContext(display, config, shareContext, contextAttribs);
    if (context == EGL_NO_CONTEXT) {
        ERR("Failed to create context for DisplaySurfaceGl.");
        return nullptr;
    }

    EGLSurface surface = s_egl.eglCreateWindowSurface(display, config, window, nullptr);
    if (surface == EGL_NO_SURFACE) {
        ERR("Failed to create window surface for DisplaySurfaceGl.");
        return nullptr;
    }

    return std::unique_ptr<DisplaySurfaceGl>(new DisplaySurfaceGl(display, surface, context));
}

DisplaySurfaceGl::DisplaySurfaceGl(EGLDisplay display,
                                   EGLSurface surface,
                                   EGLContext context)
    : mDisplay(display),
      mSurface(surface),
      mContext(context),
      mContextHelper(new DisplaySurfaceGlContextHelper(display, surface, context)) {}

DisplaySurfaceGl::~DisplaySurfaceGl() {
    if (mDisplay != EGL_NO_DISPLAY) {
        if (mSurface) {
            s_egl.eglDestroySurface(mDisplay, mSurface);
        }
        if (mContext) {
            s_egl.eglDestroyContext(mDisplay, mContext);
        }
    }
}

}  // namespace gl
}  // namespace gfxstream