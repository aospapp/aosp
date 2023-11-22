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

#ifndef DSP_PCM_GAIN_H
#define __DSP_PCM_GAIN_H__
#include "dsp_memory_config.h"
#define FRAME_SIZE		 480					//  10 ms @ 48 KHz.
#define HOLD_BUF_SIZE  228404
#define 	MSG_RECV	1		//  Msg received
#define 	MSG_PROC	2		//  Msg processed.
#define     MSG_COMP    8
#define     MSG_INCOMP  9
#define MUSIC_DATA_MEM_READ_ADDR		HIFI_MUSIC_DATA_LOCATION //  Address of the Music data memory..   !!! change this if necessary..
#define PCM_DATA_MEM_WRITE_ADDR		PCM_PLAY_BUFF_LOCATION		//  Address of some random element in PCM data memory..   !!! change this if necessary..
int ReadData( char *pDst,  int Size );
int WriteData( char *pSrc,  int Size );
int processAudio( int *pDst,  int *pSrc,  int Size );
#endif
