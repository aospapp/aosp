/* Copyright 2018 The Chromium OS Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

#include <syslog.h>

#include "buffer_share.h"
#include "cras_audio_area.h"
#include "cras_dsp_pipeline.h"
#include "cras_mix.h"
#include "cras_rstream.h"
#include "cras_system_state.h"
#include "dsp_util.h"
#include "input_data.h"
#include "utlist.h"

void input_data_run(struct ext_dsp_module *ext,
		    unsigned int nframes)
{
	struct input_data *data = (struct input_data *)ext;
	float *const *wp;
	int i;
	unsigned int writable;
	unsigned int offset = 0;

	while (nframes) {
		writable = float_buffer_writable(data->fbuffer);
		writable = MIN(nframes, writable);
		if (!writable) {
			syslog(LOG_ERR, "Not enough space to process input data");
			break;
		}
		wp = float_buffer_write_pointer(data->fbuffer);
		for (i = 0; i < data->fbuffer->num_channels; i++)
			memcpy(wp[i], ext->ports[i] + offset, writable * sizeof(float));

		float_buffer_written(data->fbuffer, writable);
		nframes -= writable;
		offset += writable;
	}
}

void input_data_configure(struct ext_dsp_module *ext, unsigned int buffer_size,
			  unsigned int num_channels, unsigned int rate)
{
	struct input_data *data = (struct input_data *)ext;

	if (data->fbuffer)
		float_buffer_destroy(&data->fbuffer);
	data->fbuffer = float_buffer_create(buffer_size, num_channels);
}

struct input_data *input_data_create(void *dev_ptr)
{
	struct input_data *data = (struct input_data *)calloc(1, sizeof(*data));

	data->dev_ptr = dev_ptr;

	data->ext.run = input_data_run;
	data->ext.configure = input_data_configure;

	return data;
}

void input_data_destroy(struct input_data **data)
{
	if ((*data)->fbuffer)
		float_buffer_destroy(&(*data)->fbuffer);
	free(*data);
	*data = NULL;
}

void input_data_set_all_streams_read(struct input_data *data,
				     unsigned int nframes)
{
	if (!data->fbuffer)
		return;

	if (float_buffer_level(data->fbuffer) < nframes) {
		syslog(LOG_ERR, "All streams read %u frames exceeds %u"
		       " in input_data's buffer",
		       nframes, float_buffer_level(data->fbuffer));
		float_buffer_reset(data->fbuffer);
		return;
	}
	float_buffer_read(data->fbuffer, nframes);
}

int input_data_get_for_stream(struct input_data *data,
			      struct cras_rstream *stream,
			      struct buffer_share *offsets,
			      struct cras_audio_area **area,
			      unsigned int *offset)
{
	unsigned int apm_processed;
	struct cras_apm *apm;

	/*
	 * It is possible that area buffer frames is smaller than the
	 * offset of stream. In this case, just reset the offset value
	 * to area->frames to prevent caller using these information get
	 * bad access to data.
	 */
	*area = data->area;
	*offset = MIN(buffer_share_id_offset(offsets, stream->stream_id),
		      data->area->frames);

	apm = cras_apm_list_get(stream->apm_list, data->dev_ptr);
	if (apm == NULL)
		return 0;

	apm_processed = cras_apm_list_process(apm, data->fbuffer, *offset);
	if (apm_processed < 0) {
		cras_apm_list_remove(stream->apm_list, apm);
		return 0;
	}
	buffer_share_offset_update(offsets, stream->stream_id, apm_processed);
	*area = cras_apm_list_get_processed(apm);
	*offset = 0;

	return 0;
}

int input_data_put_for_stream(struct input_data *data,
			      struct cras_rstream *stream,
			      struct buffer_share *offsets,
			      unsigned int frames)
{
	struct cras_apm *apm = cras_apm_list_get(
			stream->apm_list, data->dev_ptr);

	if (apm)
		cras_apm_list_put_processed(apm, frames);
	else
		buffer_share_offset_update(offsets, stream->stream_id, frames);

	return 0;
}
