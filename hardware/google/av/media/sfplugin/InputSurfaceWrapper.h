/*
 * Copyright 2018, The Android Open Source Project
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

#ifndef INPUT_SURFACE_WRAPPER_H_
#define INPUT_SURFACE_WRAPPER_H_

#include <codec2/hidl/client.h>

namespace android {

/**
 * Wrapper interface around InputSurface.
 */
class InputSurfaceWrapper {
public:
    virtual ~InputSurfaceWrapper() = default;

    /**
     * Connect the surface with |comp| and start pushing buffers. A surface can
     * connect to at most one component at a time.
     *
     * \return OK               successfully connected to |comp|
     * \return ALREADY_EXISTS   already connected to another component.
     */
    virtual status_t connect(
            const std::shared_ptr<Codec2Client::Component> &comp) = 0;

    /**
     * Disconnect the surface from the component if any.
     */
    virtual void disconnect() = 0;

    /**
     * Ref: GraphicBufferSource::signalEndOfInputStream.
     */
    virtual status_t signalEndOfInputStream() = 0;

    /// Input Surface configuration
    struct Config {
        // IN PARAMS (GBS)
        float mMinFps; // minimum fps (repeat frame to achieve this)
        float mMaxFps; // max fps (via frame drop)
        float mCaptureFps; // capture fps
        bool mSuspended; // suspended
        int64_t mTimeOffsetUs; // time offset (input => codec)
        int64_t mSuspendAtUs; // suspend/resume time
        int64_t mStartAtUs; // start time
        bool mStopped; // stopped
        int64_t mStopAtUs; // stop time

        // OUT PARAMS (GBS)
        int64_t mInputDelayUs; // delay between encoder input and surface input

        // IN PARAMS (CODEC WRAPPER)
        float mFixedAdjustedFps; // fixed fps via PTS manipulation
        float mMinAdjustedFps; // minimum fps via PTS manipulation
    };

    /**
     * Configures input surface.
     *
     * \param config configuration. This can be updated during this call to provide output
     *               parameters, but not to provide configured parameters (to avoid continually
     *               reconfiguring)
     */
    virtual status_t configure(Config &config) = 0;
};

}  // namespace android

#endif  // INPUT_SURFACE_WRAPPER_H_
