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
#include "audio/xa-audio-decoder-api.h"

#include "xa_playback.h"

#define PRINT_USAGE do { fprintf(stderr, "\nUsage: %s <input-file>\n", argv[0]); \
                         fprintf(stderr, "       Only .ogg and .pcm files are supported. \n"); \
                         fprintf(stderr, "       Playback is configured @ 48kHz, 2 ch, 16 bits pcm. \n"); \
                         fprintf(stderr, "       pcm output is written to dec-out.pcm, by default. \n\n"); \
                       } while(0)

    
#define NUM_COMP_IN_GRAPH   1

void *p_input, *p_output;
pthread_t dec_thread;

FILE *fp, *ofp=NULL;
xaf_info_t comp_info;
void *pb_handle = NULL;
unsigned int card = 0;
unsigned int device = 0;
unsigned int period_size = 1024;
unsigned int period_count = 4;

/* ...playback format */    
xaf_format_t pb_format;

int g_pthread_exit_code=0x12345678;

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
    xaf_comp_status comp_status;
    int input_over, read_length;
    void *pg_pthread_exit_code = (void*)&g_pthread_exit_code;

    XAF_CHK_PTR(arg);
    XAF_CHK_PTR(p_input);
    XAF_CHK_PTR(p_output);

    p_comp = arg;
    input_over = 0;
    
    XF_CHK_API(xaf_comp_process(NULL, p_comp, NULL, 0, XAF_EXEC_FLAG));

    while (1)
    {
	
        XF_CHK_API(xaf_comp_get_status(NULL, p_comp, &comp_status, &comp_info));
        
        if (comp_status == XAF_EXEC_DONE) break;

        if (comp_status == XAF_NEED_INPUT && !input_over)
        {
            void *p_buf = (void *)comp_info.buf; 
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
            void *p_buf = (void *)comp_info.buf;
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
    void *p_decoder;
    mem_obj_t* mem_handle;
    int num_comp = NUM_COMP_IN_GRAPH;

    xaf_comp_status dec_status;
    void *dec_inbuf[2];
    int buf_length = XAF_INBUF_SIZE;
    int read_length;
    int i;    
    
    xf_id_t dec_id;
    int (*dec_setup)(void *p_comp);    
    const char *ext;
    int pthread_error;
    void *pthread_exit_code;
 
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

    print_banner("\'Audio decoder(PCM/Ogg-Vorbis)\'");

    /* ...initialize tracing facility */
    TRACE_INIT("Xtensa Audio Framework - Sample Application");

#if !defined BOARD
    /* ...check input arguments */
    if (argc != 2)
    {
        TRACE(ERROR, _b("Usage: ./xaf-test <input-file-.ogg/.pcm>\n"));
        PRINT_USAGE;
        return 0;
    }
    
        
    ext = strrchr(argv[1], '.');
    if (!ext)
    {   
        PRINT_USAGE;
        return 0;
    }
    ext++;
    if (!strcmp(ext, "pcm")) {
    	dec_id    = "audio-decoder/pcm";
        dec_setup = pcm_setup;
    }
    else if (!strcmp(ext, "ogg")) {
    	dec_id    = "audio-decoder/vorbis";
        dec_setup = vorbis_setup;
    }
    else {
    	TRACE(ERROR, _x("Unknown Decoder Extension '%s'"), ext);
        PRINT_USAGE;
        exit(-1);
    }

    /* ...open file */
    if ((fp = fopen(argv[1], "rb")) == NULL)
    {
       TRACE(ERROR, _x("Failed to open '%s': %d"), argv[1], errno);
       exit(-1);
    }
    if ((ofp = fopen("/data/dec-out.pcm", "wb")) == NULL)
    {
       TRACE(ERROR, _x("Failed to open '%s': %d"), "/data/dec-out.pcm", errno);
       exit(-1);
    }
    p_input  = fp;
    p_output = ofp;
#endif

    mem_handle = mem_init(); //initialize memory handler

    XF_CHK_API(xaf_adev_open(&p_adev, audio_frmwk_buf_size, audio_comp_buf_size, mem_malloc, mem_free));

    /* ...create decoder component */
    XF_CHK_API(xaf_comp_create(p_adev, &p_decoder, dec_id, 2, 1, &dec_inbuf[0], XAF_DECODER));
    XF_CHK_API(dec_setup(p_decoder));

    /* ...start decoder component */            
    XF_CHK_API(xaf_comp_process(p_adev, p_decoder, NULL, 0, XAF_START_FLAG));

    /* ...feed input to decoder component */
    for (i=0; i<2; i++) 
    {
        XF_CHK_API(read_input(dec_inbuf[i], buf_length, &read_length, p_input));

        if (read_length) 
            XF_CHK_API(xaf_comp_process(p_adev, p_decoder, dec_inbuf[i], read_length, XAF_INPUT_READY_FLAG));
        else 
            break;
    }

    /* ...initialization loop */    
    while (1)
    {
	    XF_CHK_API(xaf_comp_get_status(p_adev, p_decoder, &dec_status, &comp_info));

	    if (dec_status == XAF_INIT_DONE || dec_status == XAF_EXEC_DONE)
	    {
		    pb_handle = xa_playback_open(card, device, pb_format.channels, pb_format.sample_rate,
				         pb_format.pcm_width, period_size, period_count); 
		    if (!pb_handle) {
			    TRACE(ERROR, _x("Playback open error\n"));
			    return -1;
		    }

		    break;
	    }

	    if (dec_status == XAF_NEED_INPUT)
	    {
		    void *p_buf = (void *) comp_info.buf; 
		    int size    = comp_info.length;

		    XF_CHK_API(read_input(p_buf, size, &read_length, p_input));

		    if (read_length) 
			    XF_CHK_API(xaf_comp_process(p_adev, p_decoder, p_buf, read_length, XAF_INPUT_READY_FLAG));
		    else
			    break;
	    }
    }
    
    if (dec_status != XAF_INIT_DONE)
    {
        TRACE(ERROR, _x("Failed to init"));
        exit(-1);
    }

    pthread_create(&dec_thread, 0, (void *(*)(void *))&comp_process_entry, p_decoder);

    pthread_error = pthread_join(dec_thread, (void **) &pthread_exit_code);
    if(pthread_error)
    {
      TRACE(ERROR, _b("decode thread join error:%x\n"), pthread_error);
    }

    /* ...exec done, clean-up */
    XF_CHK_API(xaf_comp_delete(p_decoder));
    xa_playback_close(pb_handle);
    XF_CHK_API(xaf_adev_close(p_adev, 0 /*unused*/));
    if (fp)  fclose(fp);
    if (ofp) fclose(ofp);
    
    mem_exit();
    XF_CHK_API(print_mem_mcps_info(mem_handle, num_comp));

    return 0;
}

