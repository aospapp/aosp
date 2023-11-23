/*******************************************************************************
* Copyright (C) 2018 Cadence Design Systems, Inc.
* 
* Permission is hereby granted, free of charge, to any person obtaining
* a copy of this software and associated documentation files (the
* "Software"), to use this Software with Cadence processor cores only and 
* not with any other processors and platforms, subject to
* the following conditions:
* 
* The above copyright notice and this permission notice shall be included
* in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
* MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
* IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
* CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
* TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
* SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

******************************************************************************/

#define MODULE_TAG                      UTEST

#include "xaf-utils-test.h"

#include "audio/xa_vorbis_dec_api.h"
#include "audio/xa-mixer-api.h"
#include "audio/xa-audio-decoder-api.h"

#include "xa_playback.h"

#define PRINT_USAGE do { fprintf(stderr, "\nUsage: %s <input-file1> <input-file2>\n", argv[0]); \
                         fprintf(stderr, "       Only .ogg and .pcm files are supported. \n"); \
                         fprintf(stderr, "       Playback is configured @ 48kHz, 2 ch, 16 bits pcm. \n"); \
                         fprintf(stderr, "       <input-file2> is optional. \n"); \
                         fprintf(stderr, "       pcm output is written to dec-mix-out.pcm, by default. \n\n"); \
                       } while(0)

#define MAX_INP_STRMS       2
#define NUM_COMP_IN_GRAPH   3

/* ...global variables */
int g_pthread_exit_code=0x12345678;

/* ...playback format */    
xaf_format_t pb_format;

/* ...playback device parameters */
void *pb_handle = NULL;

void thread_exit_handler(int sig)
{
    /* ...unused arg */
    (void) sig;

    pthread_exit(0);
}

static int vorbis_setup(void *p_decoder)
{
    int param[2];
    
    /* 1: Raw decode, 0: Ogg decode */
    param[0] = XA_VORBISDEC_CONFIG_PARAM_RAW_VORBIS_FILE_MODE;
    param[1] = 0;

    XF_CHK_API(xaf_comp_set_config(p_decoder, 1, &param[0]));    
    
    return 0;
}

static int pcm_setup(void *p_pcm)
{
    int param[6];

    param[0] = XA_CODEC_CONFIG_PARAM_SAMPLE_RATE;
    param[1] = pb_format.sample_rate;
    param[2] = XA_CODEC_CONFIG_PARAM_CHANNELS;
    param[3] = pb_format.channels;
    param[4] = XA_CODEC_CONFIG_PARAM_PCM_WIDTH;
    param[5] = pb_format.pcm_width;

    XF_CHK_API(xaf_comp_set_config(p_pcm, 3, &param[0]));    

    return 0;
}

static int mixer_setup(void *p_decoder, xaf_format_t *p_format)
{
    int param[6];

    param[0] = XA_MIXER_CONFIG_PARAM_SAMPLE_RATE;
    param[1] = p_format->sample_rate;
    param[2] = XA_MIXER_CONFIG_PARAM_CHANNELS;
    param[3] = p_format->channels;
    param[4] = XA_MIXER_CONFIG_PARAM_PCM_WIDTH;
    param[5] = p_format->pcm_width;

    XF_CHK_API(xaf_comp_set_config(p_decoder, 3, &param[0]));    

    return 0;
}

static int consume_output(void *p_buf, int buf_length, void *p_output)
{
    XAF_CHK_PTR(p_buf);
    XAF_CHK_PTR(p_output);

#if !defined BOARD
    FILE *fp = p_output;
    fwrite(p_buf, 1, buf_length, fp);    

    if (xa_playback_buf(pb_handle, p_buf, buf_length)) {
        TRACE(ERROR, _b("Playback Failed \n"));
	return -1;
    }
#else 
#endif
    return 0;
}

static int read_input(void *p_buf, int buf_length, int *read_length, void *p_input)
{
    XAF_CHK_PTR(p_buf);
    XAF_CHK_PTR(read_length);
    XAF_CHK_PTR(p_input);

#if !defined BOARD
    FILE *fp = p_input;
    *read_length = fread(p_buf, 1, buf_length, fp);
#else
#endif
    return 0;
}

static int comp_process_entry(void *arg)
{
    void *p_comp;
    void *p_input, *p_output;
    xaf_comp_status comp_status;
    xaf_info_t comp_info;
    int input_over, read_length;
    void * (*arg_arr)[3];
    void *pg_pthread_exit_code = (void*)&g_pthread_exit_code;

    XAF_CHK_PTR(arg);

    arg_arr = arg;
    p_comp   = (*arg_arr)[0];
    p_input  = (*arg_arr)[1];
    p_output = (*arg_arr)[2];
    input_over = 0;
    
    XF_CHK_API(xaf_comp_process(NULL, p_comp, NULL, 0, XAF_EXEC_FLAG));
    
    while (1)
    {
        XF_CHK_API(xaf_comp_get_status(NULL, p_comp, &comp_status, &comp_info));
        
        if (comp_status == XAF_EXEC_DONE) break;

        if (comp_status == XAF_NEED_INPUT && !input_over)
        {
            void *p_buf = (void *) comp_info.buf; 
            int size    = comp_info.length;
            
            XF_CHK_API(read_input(p_buf, size, &read_length, p_input));

            if (read_length) 
                XF_CHK_API(xaf_comp_process(NULL, p_comp, (void *)comp_info.buf, read_length, XAF_INPUT_READY_FLAG));
            else
            {
                XF_CHK_API(xaf_comp_process(NULL, p_comp, NULL, 0, XAF_INPUT_OVER_FLAG));
                input_over = 1;
            }
        }
        
        if (comp_status == XAF_OUTPUT_READY)
        {
            void *p_buf = (void *) comp_info.buf;
            int size    = comp_info.length;
            
            XF_CHK_API(consume_output(p_buf, size, p_output));
            XF_CHK_API(xaf_comp_process(NULL, p_comp, (void *)comp_info.buf, comp_info.length, XAF_NEED_OUTPUT_FLAG));
        }
    }
    pthread_exit(pg_pthread_exit_code);

    return 0;
}

int main(int argc, const char **argv)
{
    void *p_adev = NULL;
    void *p_decoder[MAX_INP_STRMS];
    void *p_mixer;
    mem_obj_t* mem_handle;
    int num_comp = NUM_COMP_IN_GRAPH;
    
    xaf_comp_status dec_status;
    xaf_info_t comp_info;

    void *p_input[MAX_INP_STRMS], *p_output;
        
    xf_id_t dec_id[MAX_INP_STRMS];
    int (*dec_setup[MAX_INP_STRMS])(void *p_comp);    

    pthread_t dec_thread[MAX_INP_STRMS];
    pthread_t mixer_thread;
    void *dec_thread_args[MAX_INP_STRMS][3];
    void *mixer_thread_args[3];

    const char *ext; 
    FILE *fp, *ofp;
    void *dec_inbuf[MAX_INP_STRMS][2];
    int buf_length = XAF_INBUF_SIZE;
    int read_length;
    int input_over = 0;
    int i, j;   
    int num_strms; 

    unsigned int card = 0;
    unsigned int device = 0;
    unsigned int period_size = 1024;
    unsigned int period_count = 4;

    int pthread_error;
    void *pthread_exit_code[3];

    struct sigaction actions;
    memset(&actions, 0, sizeof(actions));
    sigemptyset(&actions.sa_mask);
    actions.sa_flags = 0;
    actions.sa_handler = thread_exit_handler;
    sigaction(SIGUSR1,&actions,NULL);
 
    /* ...initialize playback format */
    pb_format.sample_rate = 48000;
    pb_format.channels    = 2;
    pb_format.pcm_width   = 16;

    audio_frmwk_buf_size = 0; //unused
    audio_comp_buf_size  = 0; //unused

    print_banner("\'Audio decoder(PCM/Ogg-Vorbis) + Mixer\'");

    /* ...initialize tracing facility */
    TRACE_INIT("Xtensa Audio Framework - Sample Application");

#if !defined BOARD
    /* ...check input arguments */
    if (argc < 2 || argc > (MAX_INP_STRMS+1))
    {
        TRACE(ERROR, _b("Usage: ./xaf-test <infile1> <infile2>\n"));
        PRINT_USAGE;
        return 0;
    }
    
    argc--; 
    for (i=0; i<argc; i++)
    { 
        ext = strrchr(argv[i+1], '.');
        if (!ext)
        {   
            PRINT_USAGE;
            return 0;
        }
        ext++;
        if (!strcmp(ext, "pcm")) {
     	    dec_id[i]    = "audio-decoder/pcm";
            dec_setup[i] = pcm_setup;
        }
        else if (!strcmp(ext, "ogg")) {
            dec_id[i]    = "audio-decoder/vorbis";
            dec_setup[i] = vorbis_setup;
        }
        else {
           TRACE(ERROR, _x("Unknown Decoder Extension '%s'"), ext);
           PRINT_USAGE;
           exit(-1);
        }
        /* ...open file */
        if ((fp = fopen(argv[i+1], "rb")) == NULL)
        {
           TRACE(ERROR, _x("Failed to open '%s': %d"), argv[i+1], errno);
           exit(-1);
        }
        p_input[i] = fp;
    }
    num_strms = i;

    if ((ofp = fopen("dec-mix-out.pcm", "wb")) == NULL)
    {
       TRACE(ERROR, _x("Failed to open '%s': %d"), "dec-mix-out.pcm", errno);
       exit(-1);
    }
    p_output = ofp;
#endif

    mem_handle = mem_init(); //initialize memory handler

    XF_CHK_API(xaf_adev_open(&p_adev, audio_frmwk_buf_size, audio_comp_buf_size, mem_malloc, mem_free));

    /* ...create mixer component */
    XF_CHK_API(xaf_comp_create(p_adev, &p_mixer, "mixer", 0, 1, NULL, XAF_MIXER));
    XF_CHK_API(mixer_setup(p_mixer, &pb_format));

    for (i=0; i<num_strms; i++)
    {
        /* ...create decoder component */
        XF_CHK_API(xaf_comp_create(p_adev, &p_decoder[i], dec_id[i], 2, 0, &dec_inbuf[i][0], XAF_DECODER));
        XF_CHK_API((dec_setup[i])(p_decoder[i]));

    	/* ...start decoder component */            
        XF_CHK_API(xaf_comp_process(p_adev, p_decoder[i], NULL, 0, XAF_START_FLAG));
    	
	/* ...feed input to decoder component */
        for (j=0; j<2; j++) 
        {
            XF_CHK_API(read_input(dec_inbuf[i][j], buf_length, &read_length, p_input[i]));

            if (read_length) 
                XF_CHK_API(xaf_comp_process(p_adev, p_decoder[i], dec_inbuf[i][j], read_length, XAF_INPUT_READY_FLAG));
            else
                break;
        }
    
    	/* ...initialization loop */    
        while (1)
        {
            XF_CHK_API(xaf_comp_get_status(p_adev, p_decoder[i], &dec_status, &comp_info));
        
            if (dec_status == XAF_INIT_DONE || dec_status == XAF_EXEC_DONE) break;

            if (dec_status == XAF_NEED_INPUT && !input_over)
            {
                void *p_buf = (void *) comp_info.buf; 
                int size    = comp_info.length;
            
                XF_CHK_API(read_input(p_buf, size, &read_length, p_input[i]));

                if (read_length) 
                    XF_CHK_API(xaf_comp_process(p_adev, p_decoder[i], p_buf, read_length, XAF_INPUT_READY_FLAG));
                else
                    break;
            }
        }

        if (dec_status != XAF_INIT_DONE)
        {
            TRACE(ERROR, _x("Failed to init"));
            exit(-1);
        }

        XF_CHK_API(xaf_connect(p_decoder[i], p_mixer, 4));
    }

    XF_CHK_API(xaf_comp_process(p_adev, p_mixer, NULL, 0, XAF_START_FLAG));
    XF_CHK_API(xaf_comp_get_status(p_adev, p_mixer, &dec_status, &comp_info));
            
    if (dec_status != XAF_INIT_DONE)
    {
        TRACE(ERROR, _x("Failed to init"));
        exit(-1);
    }
    
    /* ...open playback device */
    pb_handle = xa_playback_open(card, device, pb_format.channels, pb_format.sample_rate,
		  	         pb_format.pcm_width, period_size, period_count); 
    if (!pb_handle) {
        TRACE(ERROR, _x("Playback open error\n"));
	return -1;
    }

    for (i=0; i<num_strms; i++)
    {
        dec_thread_args[i][0] = p_decoder[i];
        dec_thread_args[i][1] = p_input[i];
        dec_thread_args[i][2] = p_output; 
        pthread_create(&dec_thread[i], 0, (void *(*)(void*))&comp_process_entry, dec_thread_args[i]);
    }
        
    mixer_thread_args[0] = p_mixer;
    mixer_thread_args[1] = NULL; 
    mixer_thread_args[2] = p_output; 
    pthread_create(&mixer_thread, 0, (void *(*)(void*))comp_process_entry, &mixer_thread_args[0]);

    for (i=0; i<num_strms; i++)
    {
      pthread_error = pthread_join(dec_thread[i], (void **) &pthread_exit_code[i]);
      if(pthread_error)
      {
        TRACE(ERROR, _b("decode thread %d join error:%x\n"), i, pthread_error);
      }
    }
    pthread_error = pthread_join(mixer_thread, (void **) &pthread_exit_code[i]);
    if(pthread_error)
    {
      TRACE(ERROR, _b("mixer thread join error:%x\n"), pthread_error);
    }

    for (i=0; i<num_strms; i++)
    {
        XF_CHK_API(xaf_comp_delete(p_decoder[i]));
        if (p_input[i]) fclose(p_input[i]);
    }
    XF_CHK_API(xaf_comp_delete(p_mixer));

    /* ...exec done, clean-up */
    xa_playback_close(pb_handle);

    XF_CHK_API(xaf_adev_close(p_adev, 0 /*unused*/));
    if (p_output) fclose(p_output);
    
    mem_exit();
    XF_CHK_API(print_mem_mcps_info(mem_handle, num_comp));

    return 0;
}

