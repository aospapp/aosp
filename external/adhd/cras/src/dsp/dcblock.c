/* Copyright 2017 The Chromium OS Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

#include <stdlib.h>
#include "dcblock.h"

struct dcblock {
        float R;
        float x_prev;
        float y_prev;
};

struct dcblock *dcblock_new(float R)
{
	struct dcblock *dcblock = (struct dcblock *)calloc(1, sizeof(*dcblock));
	dcblock->R = R;
	return dcblock;
}

void dcblock_free(struct dcblock *dcblock)
{
	free(dcblock);
}

/* This is the prototype of the processing loop. */
void dcblock_process(struct dcblock *dcblock, float *data, int count)
{
	int n;
	float x_prev = dcblock->x_prev;
	float y_prev = dcblock->y_prev;
	float R = dcblock->R;
	for (n = 0; n < count; n++) {
		float x = data[n];
		data[n] = x - x_prev + R * y_prev;
		y_prev = data[n];
		x_prev = x;
	}
	dcblock->x_prev = x_prev;
	dcblock->y_prev = y_prev;
}
