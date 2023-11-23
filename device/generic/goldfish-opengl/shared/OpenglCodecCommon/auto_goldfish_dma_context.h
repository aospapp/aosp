/*
 * Copyright (C) 2018 Google, Inc.
 *
 * This software is licensed under the terms of the GNU General Public
 * License version 2, as published by the Free Software Foundation, and
 * may be copied, distributed, and modified under those terms.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 */

#ifndef ANDROID_INCLUDE_HARDWARE_AUTO_GOLDFISH_DMA_CONTEXT_H
#define ANDROID_INCLUDE_HARDWARE_AUTO_GOLDFISH_DMA_CONTEXT_H

#include <inttypes.h>
#include "goldfish_dma.h"

// A C++ wrapper for goldfish_dma_context that releases resources in dctor.
class AutoGoldfishDmaContext {
public:
    AutoGoldfishDmaContext();
    explicit AutoGoldfishDmaContext(goldfish_dma_context *ctx);
    ~AutoGoldfishDmaContext();

    const goldfish_dma_context &get() const { return m_ctx; }
    void reset(goldfish_dma_context *ctx);
    goldfish_dma_context release();

private:
    AutoGoldfishDmaContext(const AutoGoldfishDmaContext &rhs);
    AutoGoldfishDmaContext& operator=(const AutoGoldfishDmaContext &rhs);

    goldfish_dma_context m_ctx;
};

#endif  // ANDROID_INCLUDE_HARDWARE_AUTO_GOLDFISH_DMA_CONTEXT_H
