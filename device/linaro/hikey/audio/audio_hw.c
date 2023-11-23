/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "audio_hw_hikey"
//#define LOG_NDEBUG 0

#include <errno.h>
#include <malloc.h>
#include <pthread.h>
#include <stdint.h>
#include <sys/time.h>
#include <stdlib.h>
#include <unistd.h>

#include <log/log.h>
#include <cutils/str_parms.h>
#include <cutils/properties.h>

#include <hardware/hardware.h>
#include <system/audio.h>
#include <hardware/audio.h>

#include <sound/asound.h>
#include <tinyalsa/asoundlib.h>
#include <audio_utils/resampler.h>
#include <audio_utils/echo_reference.h>
#include <hardware/audio_effect.h>
#include <hardware/audio_alsaops.h>
#include <audio_effects/effect_aec.h>

#include <sys/ioctl.h>
#include <linux/audio_hifi.h>

#define CARD_OUT 0
#define PORT_CODEC 0
/* Minimum granularity - Arbitrary but small value */
#define CODEC_BASE_FRAME_COUNT 32

/* number of base blocks in a short period (low latency) */
#define PERIOD_MULTIPLIER 32  /* 21 ms */
/* number of frames per short period (low latency) */
#define PERIOD_SIZE (CODEC_BASE_FRAME_COUNT * PERIOD_MULTIPLIER)
/* number of pseudo periods for low latency playback */
#define PLAYBACK_PERIOD_COUNT 4
#define PLAYBACK_PERIOD_START_THRESHOLD 2
#define CODEC_SAMPLING_RATE 48000
#define CHANNEL_STEREO 2
#define MIN_WRITE_SLEEP_US      5000

#ifdef ENABLE_XAF_DSP_DEVICE
#include "xaf-utils-test.h"
#include "audio/xa_vorbis_dec_api.h"
#include "audio/xa-audio-decoder-api.h"
#define NUM_COMP_IN_GRAPH   1

struct alsa_audio_device;

struct xaf_dsp_device {
    void *p_adev;
    void *p_decoder;
    xaf_info_t comp_info;
    /* ...playback format */
    xaf_format_t pb_format;
    xaf_comp_status dec_status;
    int dec_info[4];
    void *dec_inbuf[2];
    int read_length;
    xf_id_t dec_id;
    int xaf_started;
    mem_obj_t* mem_handle;
    int num_comp;
    int (*dec_setup)(void *p_comp, struct alsa_audio_device *audio_device);
    int xafinitdone;
};
#endif

struct stub_stream_in {
    struct audio_stream_in stream;
};

struct alsa_audio_device {
    struct audio_hw_device hw_device;

    pthread_mutex_t lock;   /* see note below on mutex acquisition order */
    int devices;
    struct alsa_stream_in *active_input;
    struct alsa_stream_out *active_output;
    bool mic_mute;
#ifdef ENABLE_XAF_DSP_DEVICE
    struct xaf_dsp_device dsp_device;
    int hifi_dsp_fd;
#endif
};

struct alsa_stream_out {
    struct audio_stream_out stream;

    pthread_mutex_t lock;   /* see note below on mutex acquisition order */
    struct pcm_config config;
    struct pcm *pcm;
    bool unavailable;
    int standby;
    struct alsa_audio_device *dev;
    int write_threshold;
    unsigned int written;
};

#ifdef ENABLE_XAF_DSP_DEVICE
static int pcm_setup(void *p_pcm, struct alsa_audio_device *audio_device)
{
    int param[6];

    param[0] = XA_CODEC_CONFIG_PARAM_SAMPLE_RATE;
    param[1] = audio_device->dsp_device.pb_format.sample_rate;
    param[2] = XA_CODEC_CONFIG_PARAM_CHANNELS;
    param[3] = audio_device->dsp_device.pb_format.channels;
    param[4] = XA_CODEC_CONFIG_PARAM_PCM_WIDTH;
    param[5] = audio_device->dsp_device.pb_format.pcm_width;

    XF_CHK_API(xaf_comp_set_config(p_pcm, 3, &param[0]));

    return 0;
}

void xa_thread_exit_handler(int sig)
{
    /* ...unused arg */
    (void) sig;

    pthread_exit(0);
}

/*xtensa audio device init*/
static int xa_device_init(struct alsa_audio_device *audio_device)
{
    /* ...initialize playback format */
    audio_device->dsp_device.p_adev = NULL;
    audio_device->dsp_device.pb_format.sample_rate = 48000;
    audio_device->dsp_device.pb_format.channels    = 2;
    audio_device->dsp_device.pb_format.pcm_width   = 16;
    audio_device->dsp_device.xafinitdone = 0;
    audio_frmwk_buf_size = 0; //unused
    audio_comp_buf_size  = 0; //unused
    audio_device->dsp_device.num_comp = NUM_COMP_IN_GRAPH;
    struct sigaction actions;
    memset(&actions, 0, sizeof(actions));
    sigemptyset(&actions.sa_mask);
    actions.sa_flags = 0;
    actions.sa_handler = xa_thread_exit_handler;
    sigaction(SIGUSR1,&actions,NULL);
    /* ...initialize tracing facility */
    audio_device->dsp_device.xaf_started =1;
    audio_device->dsp_device.dec_id    = "audio-decoder/pcm";
    audio_device->dsp_device.dec_setup = pcm_setup;
    audio_device->dsp_device.mem_handle = mem_init(); //initialize memory handler
    XF_CHK_API(xaf_adev_open(&audio_device->dsp_device.p_adev, audio_frmwk_buf_size, audio_comp_buf_size, mem_malloc, mem_free));
    /* ...create decoder component */
    XF_CHK_API(xaf_comp_create(audio_device->dsp_device.p_adev, &audio_device->dsp_device.p_decoder, audio_device->dsp_device.dec_id, 1, 1, &audio_device->dsp_device.dec_inbuf[0], XAF_DECODER));
    XF_CHK_API(audio_device->dsp_device.dec_setup(audio_device->dsp_device.p_decoder,audio_device));

    /* ...start decoder component */
    XF_CHK_API(xaf_comp_process(audio_device->dsp_device.p_adev, audio_device->dsp_device.p_decoder, NULL, 0, XAF_START_FLAG));
    return 0;
}

static int xa_device_run(struct audio_stream_out *stream, const void *buffer, size_t frame_size, size_t out_frames, size_t bytes)
{
    struct alsa_stream_out *out = (struct alsa_stream_out *)stream;
    struct alsa_audio_device *adev = out->dev;
    int ret=0;
    void *p_comp=adev->dsp_device.p_decoder;
    xaf_comp_status comp_status;
    memcpy(adev->dsp_device.dec_inbuf[0],buffer,bytes);
    adev->dsp_device.read_length=bytes;

    if (adev->dsp_device.xafinitdone == 0) {
        XF_CHK_API(xaf_comp_process(adev->dsp_device.p_adev, adev->dsp_device.p_decoder, adev->dsp_device.dec_inbuf[0], adev->dsp_device.read_length, XAF_INPUT_READY_FLAG));
        XF_CHK_API(xaf_comp_get_status(adev->dsp_device.p_adev, adev->dsp_device.p_decoder, &adev->dsp_device.dec_status, &adev->dsp_device.comp_info));
        ALOGE("PROXY:%s xaf_comp_get_status %d\n",__func__,adev->dsp_device.dec_status);
        if (adev->dsp_device.dec_status == XAF_INIT_DONE) {
            adev->dsp_device.xafinitdone = 1;
            out->written += out_frames;
            XF_CHK_API(xaf_comp_process(NULL, p_comp, NULL, 0, XAF_EXEC_FLAG));
        }
    } else {
        XF_CHK_API(xaf_comp_process(NULL, adev->dsp_device.p_decoder, adev->dsp_device.dec_inbuf[0], adev->dsp_device.read_length, XAF_INPUT_READY_FLAG));
        while (1) {
            XF_CHK_API(xaf_comp_get_status(NULL, p_comp, &comp_status, &adev->dsp_device.comp_info));
            if (comp_status == XAF_EXEC_DONE) break;
            if (comp_status == XAF_NEED_INPUT) {
                 ALOGV("PROXY:%s loop:XAF_NEED_INPUT\n",__func__);
                 break;
            }
            if (comp_status == XAF_OUTPUT_READY) {
                void *p_buf = (void *)adev->dsp_device.comp_info.buf;
                int size    = adev->dsp_device.comp_info.length;
                ret = pcm_mmap_write(out->pcm, p_buf, size);
                if (ret == 0) {
                    out->written += out_frames;
                }
                XF_CHK_API(xaf_comp_process(NULL, adev->dsp_device.p_decoder, (void *)adev->dsp_device.comp_info.buf, adev->dsp_device.comp_info.length, XAF_NEED_OUTPUT_FLAG));
            }
        }
    }
    return ret;
}

static int xa_device_close(struct alsa_audio_device *audio_device)
{
    if (audio_device->dsp_device.xaf_started) {
        xaf_comp_status comp_status;
        audio_device->dsp_device.xaf_started=0;
        while (1) {
            XF_CHK_API(xaf_comp_get_status(NULL, audio_device->dsp_device.p_decoder, &comp_status, &audio_device->dsp_device.comp_info));
            ALOGV("PROXY:comp_status:%d,audio_device->dsp_device.comp_info.length:%d\n",(int)comp_status,audio_device->dsp_device.comp_info.length);
            if (comp_status == XAF_EXEC_DONE)
                break;
            if (comp_status == XAF_NEED_INPUT) {
                XF_CHK_API(xaf_comp_process(NULL, audio_device->dsp_device.p_decoder, NULL, 0, XAF_INPUT_OVER_FLAG));
            }

            if (comp_status == XAF_OUTPUT_READY) {
                XF_CHK_API(xaf_comp_process(NULL, audio_device->dsp_device.p_decoder, (void *)audio_device->dsp_device.comp_info.buf, audio_device->dsp_device.comp_info.length, XAF_NEED_OUTPUT_FLAG));
            }
        }

        /* ...exec done, clean-up */
        XF_CHK_API(xaf_comp_delete(audio_device->dsp_device.p_decoder));
        XF_CHK_API(xaf_adev_close(audio_device->dsp_device.p_adev, 0 /*unused*/));
        mem_exit();
        XF_CHK_API(print_mem_mcps_info(audio_device->dsp_device.mem_handle, audio_device->dsp_device.num_comp));
    }
    return 0;
}
#endif

/* must be called with hw device and output stream mutexes locked */
static int start_output_stream(struct alsa_stream_out *out)
{
    struct alsa_audio_device *adev = out->dev;

    if (out->unavailable)
        return -ENODEV;

    /* default to low power: will be corrected in out_write if necessary before first write to
     * tinyalsa.
     */
    out->write_threshold = PLAYBACK_PERIOD_COUNT * PERIOD_SIZE;
    out->config.start_threshold = PLAYBACK_PERIOD_START_THRESHOLD * PERIOD_SIZE;
    out->config.avail_min = PERIOD_SIZE;

    out->pcm = pcm_open(CARD_OUT, PORT_CODEC, PCM_OUT | PCM_MMAP | PCM_NOIRQ | PCM_MONOTONIC, &out->config);

    if (!pcm_is_ready(out->pcm)) {
        ALOGE("cannot open pcm_out driver: %s", pcm_get_error(out->pcm));
        pcm_close(out->pcm);
        adev->active_output = NULL;
        out->unavailable = true;
        return -ENODEV;
    }

    adev->active_output = out;
    return 0;
}

static uint32_t out_get_sample_rate(const struct audio_stream *stream)
{
    struct alsa_stream_out *out = (struct alsa_stream_out *)stream;
    return out->config.rate;
}

static int out_set_sample_rate(struct audio_stream *stream, uint32_t rate)
{
    ALOGV("out_set_sample_rate: %d", 0);
    return -ENOSYS;
}

static size_t out_get_buffer_size(const struct audio_stream *stream)
{
    ALOGV("out_get_buffer_size: %d", 4096);

    /* return the closest majoring multiple of 16 frames, as
     * audioflinger expects audio buffers to be a multiple of 16 frames */
    size_t size = PERIOD_SIZE;
    size = ((size + 15) / 16) * 16;
    return size * audio_stream_out_frame_size((struct audio_stream_out *)stream);
}

static audio_channel_mask_t out_get_channels(const struct audio_stream *stream)
{
    ALOGV("out_get_channels");
    struct alsa_stream_out *out = (struct alsa_stream_out *)stream;
    return audio_channel_out_mask_from_count(out->config.channels);
}

static audio_format_t out_get_format(const struct audio_stream *stream)
{
    ALOGV("out_get_format");
    struct alsa_stream_out *out = (struct alsa_stream_out *)stream;
    return audio_format_from_pcm_format(out->config.format);
}

static int out_set_format(struct audio_stream *stream, audio_format_t format)
{
    ALOGV("out_set_format: %d",format);
    return -ENOSYS;
}

static int do_output_standby(struct alsa_stream_out *out)
{
    struct alsa_audio_device *adev = out->dev;

    if (!out->standby) {
        pcm_close(out->pcm);
        out->pcm = NULL;
        adev->active_output = NULL;
        out->standby = 1;
    }
    return 0;
}

static int out_standby(struct audio_stream *stream)
{
    ALOGV("out_standby");
    struct alsa_stream_out *out = (struct alsa_stream_out *)stream;
    int status;

    pthread_mutex_lock(&out->dev->lock);
    pthread_mutex_lock(&out->lock);
#ifdef ENABLE_XAF_DSP_DEVICE
    xa_device_close(out->dev);
#endif
    status = do_output_standby(out);
    pthread_mutex_unlock(&out->lock);
    pthread_mutex_unlock(&out->dev->lock);
    return status;
}

static int out_dump(const struct audio_stream *stream, int fd)
{
    ALOGV("out_dump");
    return 0;
}

static int out_set_parameters(struct audio_stream *stream, const char *kvpairs)
{
    ALOGV("out_set_parameters");
    struct alsa_stream_out *out = (struct alsa_stream_out *)stream;
    struct alsa_audio_device *adev = out->dev;
    struct str_parms *parms;
    char value[32];
    int val = 0;
    int ret = -EINVAL;

    if (kvpairs == NULL || kvpairs[0] == 0) {
        return 0;
    }

    parms = str_parms_create_str(kvpairs);

    if (str_parms_get_str(parms, AUDIO_PARAMETER_STREAM_ROUTING, value, sizeof(value)) >= 0) {
        val = atoi(value);
        pthread_mutex_lock(&adev->lock);
        pthread_mutex_lock(&out->lock);
        if (((adev->devices & AUDIO_DEVICE_OUT_ALL) != val) && (val != 0)) {
            adev->devices &= ~AUDIO_DEVICE_OUT_ALL;
            adev->devices |= val;
        }
        pthread_mutex_unlock(&out->lock);
        pthread_mutex_unlock(&adev->lock);
        ret = 0;
    }

    str_parms_destroy(parms);
    return ret;
}

static char * out_get_parameters(const struct audio_stream *stream, const char *keys)
{
    ALOGV("out_get_parameters");
    return strdup("");
}

static uint32_t out_get_latency(const struct audio_stream_out *stream)
{
    ALOGV("out_get_latency");
    struct alsa_stream_out *out = (struct alsa_stream_out *)stream;
    return (PERIOD_SIZE * PLAYBACK_PERIOD_COUNT * 1000) / out->config.rate;
}

static int out_set_volume(struct audio_stream_out *stream, float left,
        float right)
{
    ALOGV("out_set_volume: Left:%f Right:%f", left, right);
    return 0;
}

static ssize_t out_write(struct audio_stream_out *stream, const void* buffer,
        size_t bytes)
{
    int ret;
    struct alsa_stream_out *out = (struct alsa_stream_out *)stream;
    struct alsa_audio_device *adev = out->dev;
    size_t frame_size = audio_stream_out_frame_size(stream);
    size_t out_frames = bytes / frame_size;

    /* acquiring hw device mutex systematically is useful if a low priority thread is waiting
     * on the output stream mutex - e.g. executing select_mode() while holding the hw device
     * mutex
     */
    pthread_mutex_lock(&adev->lock);
    pthread_mutex_lock(&out->lock);
    if (out->standby) {
#ifdef ENABLE_XAF_DSP_DEVICE
        if (adev->hifi_dsp_fd >= 0) {
            xa_device_init(adev);
        }
#endif
        ret = start_output_stream(out);
        if (ret != 0) {
            pthread_mutex_unlock(&adev->lock);
            goto exit;
        }
        out->standby = 0;
    }

    pthread_mutex_unlock(&adev->lock);

#ifdef ENABLE_XAF_DSP_DEVICE
    /*fallback to original audio processing*/
    if (adev->dsp_device.p_adev != NULL) {
        ret = xa_device_run(stream, buffer,frame_size, out_frames, bytes);
    } else {
#endif
        ret = pcm_mmap_write(out->pcm, buffer, out_frames * frame_size);
        if (ret == 0) {
            out->written += out_frames;
        }
#ifdef ENABLE_XAF_DSP_DEVICE
    }
#endif
exit:
    pthread_mutex_unlock(&out->lock);

    if (ret != 0) {
        usleep((int64_t)bytes * 1000000 / audio_stream_out_frame_size(stream) /
                out_get_sample_rate(&stream->common));
    }

    return bytes;
}

static int out_get_render_position(const struct audio_stream_out *stream,
        uint32_t *dsp_frames)
{
    *dsp_frames = 0;
    ALOGV("out_get_render_position: dsp_frames: %p", dsp_frames);
    return -EINVAL;
}

static int out_get_presentation_position(const struct audio_stream_out *stream,
                                   uint64_t *frames, struct timespec *timestamp)
{
    struct alsa_stream_out *out = (struct alsa_stream_out *)stream;
    int ret = -1;

        if (out->pcm) {
            unsigned int avail;
            if (pcm_get_htimestamp(out->pcm, &avail, timestamp) == 0) {
                size_t kernel_buffer_size = out->config.period_size * out->config.period_count;
                int64_t signed_frames = out->written - kernel_buffer_size + avail;
                if (signed_frames >= 0) {
                    *frames = signed_frames;
                    ret = 0;
                }
            }
        }

    return ret;
}


static int out_add_audio_effect(const struct audio_stream *stream, effect_handle_t effect)
{
    ALOGV("out_add_audio_effect: %p", effect);
    return 0;
}

static int out_remove_audio_effect(const struct audio_stream *stream, effect_handle_t effect)
{
    ALOGV("out_remove_audio_effect: %p", effect);
    return 0;
}

static int out_get_next_write_timestamp(const struct audio_stream_out *stream,
        int64_t *timestamp)
{
    *timestamp = 0;
    ALOGV("out_get_next_write_timestamp: %ld", (long int)(*timestamp));
    return -EINVAL;
}

/** audio_stream_in implementation **/
static uint32_t in_get_sample_rate(const struct audio_stream *stream)
{
    ALOGV("in_get_sample_rate");
    return 8000;
}

static int in_set_sample_rate(struct audio_stream *stream, uint32_t rate)
{
    ALOGV("in_set_sample_rate: %d", rate);
    return -ENOSYS;
}

static size_t in_get_buffer_size(const struct audio_stream *stream)
{
    ALOGV("in_get_buffer_size: %d", 320);
    return 320;
}

static audio_channel_mask_t in_get_channels(const struct audio_stream *stream)
{
    ALOGV("in_get_channels: %d", AUDIO_CHANNEL_IN_MONO);
    return AUDIO_CHANNEL_IN_MONO;
}

static audio_format_t in_get_format(const struct audio_stream *stream)
{
    return AUDIO_FORMAT_PCM_16_BIT;
}

static int in_set_format(struct audio_stream *stream, audio_format_t format)
{
    return -ENOSYS;
}

static int in_standby(struct audio_stream *stream)
{
    return 0;
}

static int in_dump(const struct audio_stream *stream, int fd)
{
    return 0;
}

static int in_set_parameters(struct audio_stream *stream, const char *kvpairs)
{
    return 0;
}

static char * in_get_parameters(const struct audio_stream *stream,
        const char *keys)
{
    return strdup("");
}

static int in_set_gain(struct audio_stream_in *stream, float gain)
{
    return 0;
}

static ssize_t in_read(struct audio_stream_in *stream, void* buffer,
        size_t bytes)
{
    ALOGV("in_read: bytes %zu", bytes);
    /* XXX: fake timing for audio input */
    usleep((int64_t)bytes * 1000000 / audio_stream_in_frame_size(stream) /
            in_get_sample_rate(&stream->common));
    memset(buffer, 0, bytes);
    return bytes;
}

static uint32_t in_get_input_frames_lost(struct audio_stream_in *stream)
{
    return 0;
}

static int in_add_audio_effect(const struct audio_stream *stream, effect_handle_t effect)
{
    return 0;
}

static int in_remove_audio_effect(const struct audio_stream *stream, effect_handle_t effect)
{
    return 0;
}

static int adev_open_output_stream(struct audio_hw_device *dev,
        audio_io_handle_t handle,
        audio_devices_t devices,
        audio_output_flags_t flags,
        struct audio_config *config,
        struct audio_stream_out **stream_out,
        const char *address __unused)
{
    ALOGV("adev_open_output_stream...");

    struct alsa_audio_device *ladev = (struct alsa_audio_device *)dev;
    struct alsa_stream_out *out;
    struct pcm_params *params;
    int ret = 0;

    params = pcm_params_get(CARD_OUT, PORT_CODEC, PCM_OUT);
    if (!params)
        return -ENOSYS;

    out = (struct alsa_stream_out *)calloc(1, sizeof(struct alsa_stream_out));
    if (!out)
        return -ENOMEM;

    out->stream.common.get_sample_rate = out_get_sample_rate;
    out->stream.common.set_sample_rate = out_set_sample_rate;
    out->stream.common.get_buffer_size = out_get_buffer_size;
    out->stream.common.get_channels = out_get_channels;
    out->stream.common.get_format = out_get_format;
    out->stream.common.set_format = out_set_format;
    out->stream.common.standby = out_standby;
    out->stream.common.dump = out_dump;
    out->stream.common.set_parameters = out_set_parameters;
    out->stream.common.get_parameters = out_get_parameters;
    out->stream.common.add_audio_effect = out_add_audio_effect;
    out->stream.common.remove_audio_effect = out_remove_audio_effect;
    out->stream.get_latency = out_get_latency;
    out->stream.set_volume = out_set_volume;
    out->stream.write = out_write;
    out->stream.get_render_position = out_get_render_position;
    out->stream.get_next_write_timestamp = out_get_next_write_timestamp;
    out->stream.get_presentation_position = out_get_presentation_position;

    out->config.channels = CHANNEL_STEREO;
    out->config.rate = CODEC_SAMPLING_RATE;
    out->config.format = PCM_FORMAT_S16_LE;
    out->config.period_size = PERIOD_SIZE;
    out->config.period_count = PLAYBACK_PERIOD_COUNT;

    if (out->config.rate != config->sample_rate ||
           audio_channel_count_from_out_mask(config->channel_mask) != CHANNEL_STEREO ||
               out->config.format !=  pcm_format_from_audio_format(config->format) ) {
        config->sample_rate = out->config.rate;
        config->format = audio_format_from_pcm_format(out->config.format);
        config->channel_mask = audio_channel_out_mask_from_count(CHANNEL_STEREO);
        ret = -EINVAL;
    }

    ALOGI("adev_open_output_stream selects channels=%d rate=%d format=%d",
                out->config.channels, out->config.rate, out->config.format);

    out->dev = ladev;
    out->standby = 1;
    out->unavailable = false;

    config->format = out_get_format(&out->stream.common);
    config->channel_mask = out_get_channels(&out->stream.common);
    config->sample_rate = out_get_sample_rate(&out->stream.common);

    *stream_out = &out->stream;

    /* TODO The retry mechanism isn't implemented in AudioPolicyManager/AudioFlinger. */
    ret = 0;

    return ret;
}

static void adev_close_output_stream(struct audio_hw_device *dev,
        struct audio_stream_out *stream)
{
    ALOGV("adev_close_output_stream...");
    free(stream);
}

static int adev_set_parameters(struct audio_hw_device *dev, const char *kvpairs)
{
    ALOGV("adev_set_parameters");
    return -ENOSYS;
}

static char * adev_get_parameters(const struct audio_hw_device *dev,
        const char *keys)
{
    ALOGV("adev_get_parameters");
    return strdup("");
}

static int adev_init_check(const struct audio_hw_device *dev)
{
    ALOGV("adev_init_check");
    return 0;
}

static int adev_set_voice_volume(struct audio_hw_device *dev, float volume)
{
    ALOGV("adev_set_voice_volume: %f", volume);
    return -ENOSYS;
}

static int adev_set_master_volume(struct audio_hw_device *dev, float volume)
{
    ALOGV("adev_set_master_volume: %f", volume);
    return -ENOSYS;
}

static int adev_get_master_volume(struct audio_hw_device *dev, float *volume)
{
    ALOGV("adev_get_master_volume: %f", *volume);
    return -ENOSYS;
}

static int adev_set_master_mute(struct audio_hw_device *dev, bool muted)
{
    ALOGV("adev_set_master_mute: %d", muted);
    return -ENOSYS;
}

static int adev_get_master_mute(struct audio_hw_device *dev, bool *muted)
{
    ALOGV("adev_get_master_mute: %d", *muted);
    return -ENOSYS;
}

static int adev_set_mode(struct audio_hw_device *dev, audio_mode_t mode)
{
    ALOGV("adev_set_mode: %d", mode);
    return 0;
}

static int adev_set_mic_mute(struct audio_hw_device *dev, bool state)
{
    ALOGV("adev_set_mic_mute: %d",state);
    return -ENOSYS;
}

static int adev_get_mic_mute(const struct audio_hw_device *dev, bool *state)
{
    ALOGV("adev_get_mic_mute");
    return -ENOSYS;
}

static size_t adev_get_input_buffer_size(const struct audio_hw_device *dev,
        const struct audio_config *config)
{
    ALOGV("adev_get_input_buffer_size: %d", 320);
    return 320;
}

static int adev_open_input_stream(struct audio_hw_device __unused *dev,
        audio_io_handle_t handle,
        audio_devices_t devices,
        struct audio_config *config,
        struct audio_stream_in **stream_in,
        audio_input_flags_t flags __unused,
        const char *address __unused,
        audio_source_t source __unused)
{
    struct stub_stream_in *in;

    ALOGV("adev_open_input_stream...");

    in = (struct stub_stream_in *)calloc(1, sizeof(struct stub_stream_in));
    if (!in)
        return -ENOMEM;

    in->stream.common.get_sample_rate = in_get_sample_rate;
    in->stream.common.set_sample_rate = in_set_sample_rate;
    in->stream.common.get_buffer_size = in_get_buffer_size;
    in->stream.common.get_channels = in_get_channels;
    in->stream.common.get_format = in_get_format;
    in->stream.common.set_format = in_set_format;
    in->stream.common.standby = in_standby;
    in->stream.common.dump = in_dump;
    in->stream.common.set_parameters = in_set_parameters;
    in->stream.common.get_parameters = in_get_parameters;
    in->stream.common.add_audio_effect = in_add_audio_effect;
    in->stream.common.remove_audio_effect = in_remove_audio_effect;
    in->stream.set_gain = in_set_gain;
    in->stream.read = in_read;
    in->stream.get_input_frames_lost = in_get_input_frames_lost;

    *stream_in = &in->stream;
    return 0;
}

static void adev_close_input_stream(struct audio_hw_device *dev,
        struct audio_stream_in *in)
{
    ALOGV("adev_close_input_stream...");
    return;
}

static int adev_dump(const audio_hw_device_t *device, int fd)
{
    ALOGV("adev_dump");
    return 0;
}

static int adev_close(hw_device_t *device)
{
#ifdef ENABLE_XAF_DSP_DEVICE
    struct alsa_audio_device *adev = (struct alsa_audio_device *)device;
#endif
    ALOGV("adev_close");
#ifdef ENABLE_XAF_DSP_DEVICE
    if (adev->hifi_dsp_fd >= 0)
        close(adev->hifi_dsp_fd);
#endif
    free(device);
    return 0;
}

static int adev_open(const hw_module_t* module, const char* name,
        hw_device_t** device)
{
    struct alsa_audio_device *adev;

    ALOGV("adev_open: %s", name);

    if (strcmp(name, AUDIO_HARDWARE_INTERFACE) != 0)
        return -EINVAL;

    adev = calloc(1, sizeof(struct alsa_audio_device));
    if (!adev)
        return -ENOMEM;

    adev->hw_device.common.tag = HARDWARE_DEVICE_TAG;
    adev->hw_device.common.version = AUDIO_DEVICE_API_VERSION_2_0;
    adev->hw_device.common.module = (struct hw_module_t *) module;
    adev->hw_device.common.close = adev_close;
    adev->hw_device.init_check = adev_init_check;
    adev->hw_device.set_voice_volume = adev_set_voice_volume;
    adev->hw_device.set_master_volume = adev_set_master_volume;
    adev->hw_device.get_master_volume = adev_get_master_volume;
    adev->hw_device.set_master_mute = adev_set_master_mute;
    adev->hw_device.get_master_mute = adev_get_master_mute;
    adev->hw_device.set_mode = adev_set_mode;
    adev->hw_device.set_mic_mute = adev_set_mic_mute;
    adev->hw_device.get_mic_mute = adev_get_mic_mute;
    adev->hw_device.set_parameters = adev_set_parameters;
    adev->hw_device.get_parameters = adev_get_parameters;
    adev->hw_device.get_input_buffer_size = adev_get_input_buffer_size;
    adev->hw_device.open_output_stream = adev_open_output_stream;
    adev->hw_device.close_output_stream = adev_close_output_stream;
    adev->hw_device.open_input_stream = adev_open_input_stream;
    adev->hw_device.close_input_stream = adev_close_input_stream;
    adev->hw_device.dump = adev_dump;

    adev->devices = AUDIO_DEVICE_NONE;

    *device = &adev->hw_device.common;
#ifdef ENABLE_XAF_DSP_DEVICE
    adev->hifi_dsp_fd = open(HIFI_DSP_MISC_DRIVER, O_WRONLY, 0);
    if (adev->hifi_dsp_fd < 0) {
        ALOGW("hifi_dsp: Error opening device %d", errno);
    } else {
        ALOGI("hifi_dsp: Open device");
    }
#endif
    return 0;
}

static struct hw_module_methods_t hal_module_methods = {
    .open = adev_open,
};

struct audio_module HAL_MODULE_INFO_SYM = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = AUDIO_MODULE_API_VERSION_0_1,
        .hal_api_version = HARDWARE_HAL_API_VERSION,
        .id = AUDIO_HARDWARE_MODULE_ID,
        .name = "Hikey audio HW HAL",
        .author = "The Android Open Source Project",
        .methods = &hal_module_methods,
    },
};
