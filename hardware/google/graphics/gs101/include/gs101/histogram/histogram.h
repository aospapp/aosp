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

#ifndef HISTOGRAM_H_
#define HISTOGRAM_H_

typedef enum {
    HISTOGRAM_CONTROL_INVALID = 0,
    HISTOGRAM_CONTROL_REQUEST = 1,
    HISTOGRAM_CONTROL_CANCEL = 2,
} hidl_histogram_control_t;

namespace gs101 {

class HistogramInfo {
public:
    enum Histogram_Type { HISTOGRAM_SAMPLING, HISTOGRAM_HIDL, HISTOGRAM_TYPE_NUM };
    /// Histogram ROI information, same definition as in uapi
    struct HistogramROI {
        uint16_t start_x;
        uint16_t start_y;
        uint16_t hsize;
        uint16_t vsize;
    };
    /// Histogram Weights information, same definition as in uapi
    struct HistogramWeights {
        uint16_t weight_r;
        uint16_t weight_g;
        uint16_t weight_b;
    };

    void setHistogramROI(uint16_t x, uint16_t y, uint16_t h, uint16_t v) {
        mHistogramROI.start_x = x;
        mHistogramROI.start_y = y;
        mHistogramROI.hsize = h;
        mHistogramROI.vsize = v;
    };
    const struct HistogramROI& getHistogramROI() { return mHistogramROI; }

    void setHistogramWeights(uint16_t r, uint16_t g, uint16_t b) {
        mHistogramWeights.weight_r = r;
        mHistogramWeights.weight_g = g;
        mHistogramWeights.weight_b = b;
    };
    const struct HistogramWeights& getHistogramWeights() { return mHistogramWeights; }

    void setHistogramThreshold(uint32_t t) { mHistogramThreshold = t; }
    uint32_t getHistogramThreshold() { return mHistogramThreshold; }

    Histogram_Type getHistogramType() { return mHistogramType; }

    HistogramInfo(Histogram_Type type) { mHistogramType = type; }
    virtual ~HistogramInfo() {}

private:
    Histogram_Type mHistogramType = HISTOGRAM_TYPE_NUM;
    struct HistogramROI mHistogramROI;
    struct HistogramWeights mHistogramWeights;
    uint32_t mHistogramThreshold = 0;
};

class SamplingHistogram : public HistogramInfo {
public:
    SamplingHistogram() : HistogramInfo(HISTOGRAM_SAMPLING) {}
    virtual ~SamplingHistogram() {}
};

class HIDLHistogram : public HistogramInfo {
public:
    HIDLHistogram() : HistogramInfo(HISTOGRAM_HIDL) {}
    virtual ~HIDLHistogram() {}

    virtual void CallbackHistogram(void* bin) = 0;
};

} // namespace gs101

#endif // HISTOGRAM_H_
