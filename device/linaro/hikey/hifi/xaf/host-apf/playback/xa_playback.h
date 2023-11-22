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

enum xa_playback_error {
	XA_PLAYBACK_OK		  =  0,
	XA_PLAYBACK_INVALID_PARAM = -1,
	XA_PLAYBACK_UNDERRUN      = -2,
};

void *xa_playback_open(unsigned int card, 
		       unsigned int device, 
		       unsigned int channels,
                       unsigned int rate, 
		       unsigned int bits, 
		       unsigned int period_size,
                       unsigned int period_count);

int xa_playback_buf(void *handle, 
	 	    const void *data, 
 		    unsigned int nbytes);

int xa_playback_close(void *handle);

