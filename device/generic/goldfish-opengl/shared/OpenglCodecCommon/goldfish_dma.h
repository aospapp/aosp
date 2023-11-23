/*
 * Copyright (C) 2016 Google, Inc.
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

#ifndef ANDROID_INCLUDE_HARDWARE_GOLDFISH_DMA_H
#define ANDROID_INCLUDE_HARDWARE_GOLDFISH_DMA_H

#include <inttypes.h>

// userspace interface
struct goldfish_dma_context {
	uint64_t mapped_addr;
	uint32_t size;
	int32_t fd;
};

int goldfish_dma_create_region(uint32_t sz, struct goldfish_dma_context* res);

void* goldfish_dma_map(struct goldfish_dma_context* cxt);
int goldfish_dma_unmap(struct goldfish_dma_context* cxt);

void goldfish_dma_write(struct goldfish_dma_context* cxt,
                        const void* to_write,
                        uint32_t sz);

void goldfish_dma_free(goldfish_dma_context* cxt);
uint64_t goldfish_dma_guest_paddr(const struct goldfish_dma_context* cxt);

#endif
