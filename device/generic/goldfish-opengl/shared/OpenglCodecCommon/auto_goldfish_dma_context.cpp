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

#include "auto_goldfish_dma_context.h"

namespace {
goldfish_dma_context empty() {
    goldfish_dma_context ctx;

    ctx.mapped_addr = 0;
    ctx.size = 0;
    ctx.fd = -1;

    return ctx;
}

void destroy(goldfish_dma_context *ctx) {
    if (ctx->mapped_addr) {
        goldfish_dma_unmap(ctx);
    }
    if (ctx->fd > 0) {
        goldfish_dma_free(ctx);
    }
}
}  // namespace

AutoGoldfishDmaContext::AutoGoldfishDmaContext() : m_ctx(empty()) {}

AutoGoldfishDmaContext::AutoGoldfishDmaContext(goldfish_dma_context *ctx)
    : m_ctx(*ctx) {
    *ctx = empty();
}

AutoGoldfishDmaContext::~AutoGoldfishDmaContext() {
    destroy(&m_ctx);
}

void AutoGoldfishDmaContext::reset(goldfish_dma_context *ctx) {
    destroy(&m_ctx);
    if (ctx) {
        m_ctx = *ctx;
        *ctx = empty();
    } else {
        m_ctx = empty();
    }
}

goldfish_dma_context AutoGoldfishDmaContext::release() {
    goldfish_dma_context copy = m_ctx;
    m_ctx = empty();
    return copy;
}

