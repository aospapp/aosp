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

#include <stdarg.h>
#include <stdlib.h>
#include "dsp_debug.h"
#include "dsp_memory_config.h"
#include "dsp_driver_mailbox.h"
#include "dsp_comm.h"
#include "dsp_pcm_gain.h"
extern int g_pcm_gain;
extern int MsgFlag;
extern int InpBuf[HOLD_BUF_SIZE];					// assumes 32 bit samples.
extern int OutBuf[HOLD_BUF_SIZE];				// assumes 32 bit samples.

static int hex2asc(int n)
{
	n &= 15;
	if(n > 9){
		return ('a' - 10) + n;
	} else {
		return '0' + n;
	}
}

static void xputs(const char *s, void (*xputc)(unsigned n, void *cookie), void *cookie)
{
	while (*s) {
		xputc(*s++, cookie);
	}
}

void __xprintf(const char *fmt, va_list ap,void (*xputc)(unsigned n, void *cookie), void *cookie)
{
	char scratch[32];
	for(;;){
		switch(*fmt){
		case 0:
			va_end(ap);
			return;
		case '%':
			switch(fmt[1]) {
			case 'c': {
				unsigned n = va_arg(ap, unsigned);
				xputc(n, cookie);
			fmt += 2;
				continue;
		}
			case 'h': {
				unsigned n = va_arg(ap, unsigned);
			xputc(hex2asc(n >> 12), cookie);
				xputc(hex2asc(n >> 8), cookie);
				xputc(hex2asc(n >> 4), cookie);
				xputc(hex2asc(n >> 0), cookie);
				fmt += 2;
				continue;
			}
			case 'b': {
				unsigned n = va_arg(ap, unsigned);
			xputc(hex2asc(n >> 4), cookie);
				xputc(hex2asc(n >> 0), cookie);
				fmt += 2;
				continue;
			}
			case 'p':
			case 'X':
			case 'x': {
				unsigned n = va_arg(ap, unsigned);
				char *p = scratch + 15;
				*p = 0;
				do {
					*--p = hex2asc(n);
				n = n >> 4;
				} while(n != 0);
				while(p > (scratch + 7)) *--p = '0';
				xputs(p, xputc, cookie);
				fmt += 2;
				continue;
			}
  	case 'd': {
				int n = va_arg(ap, int);
				char *p = scratch + 15;
				*p = 0;
				if(n < 0) {
					xputc('-', cookie);
				n = -n;
				}
				do {
					*--p = (n % 10) + '0';
					n /= 10;
				} while(n != 0);
				xputs(p, xputc, cookie);
				fmt += 2;
				continue;
			}

        case 'f': {
                double fnum = va_arg(ap, double);
                unsigned long long ipart, fpart;
                int i=4;
                char *p = scratch + 31;
                *p = '\0';
            
            	if(fnum < 0.0) 
                {
        		    xputc('-', cookie);
        		    fnum = -fnum;
                }
                ipart = (unsigned long long)fnum;
                fpart = ((fnum-ipart)*10000); //10^i = 10000

        		while(i>0)
                {
		            *--p = (fpart % 10) + '0';
	        	    fpart /= 10;
                    i--;
		        }
                *--p = '.';
		        while(ipart > 0)
                {
    		        *--p = (ipart % 10) + '0';
	    	        ipart /= 10;
		        }
                
                xputs(p, xputc, cookie); 
                fmt += 2;    
                continue;
            }

			case 'u': {
				unsigned n = va_arg(ap, unsigned);
				char *p = scratch + 15;
			*p = 0;
				do {
					*--p = (n % 10) + '0';
					n /= 10;
				} while(n != 0);
				xputs(p, xputc, cookie);
				fmt += 2;
				continue;
		}
			case 's': {
				char *s = (char *)va_arg(ap, char*);
				if(s == 0) s = "(null)";
				xputs(s, xputc, cookie);
				fmt += 2;
				continue;
			}
			case 'l': {
				if (fmt[2] == 'x') {
					unsigned long long n = va_arg(ap, unsigned long long);
					char *p = scratch + 23;
					*p = 0;
					do {
						*--p = hex2asc((int)n);
						n = n >> 4;
					} while(n != 0);
				while(p > (scratch + 7)) *--p = '0';
					xputs(p, xputc, cookie);
					fmt += 3;
				continue;
				}
			}
			}
			xputc(*fmt++, cookie);
			break;
		case '\n':
			xputc('\r', cookie);
		default:
			xputc(*fmt++, cookie);
		}
	}
}

static char* log_to_mem_head = 0;
void dsp_debug_init()
{
	log_to_mem_head = (char *)(*((unsigned int *)DRV_DSP_UART_TO_MEM_CUR_ADDR) + DRV_DSP_UART_TO_MEM);

}

static void log_write_to_mem(const char c_data)
{
	*log_to_mem_head = c_data;

	log_to_mem_head++;
	if ((unsigned int)log_to_mem_head >= (DRV_DSP_UART_TO_MEM + DRV_DSP_UART_TO_MEM_SIZE - 1))
		log_to_mem_head = (char *)DRV_DSP_UART_TO_MEM + DRV_DSP_UART_TO_MEM_RESERVE_SIZE;

	*((unsigned int *)DRV_DSP_UART_TO_MEM_CUR_ADDR) = (unsigned int)log_to_mem_head - DRV_DSP_UART_TO_MEM;
}


static void print_char(const char c_data)
{
	if (c_data == '\n')
		log_write_to_mem('\r');
	log_write_to_mem(c_data);
}

typedef void (*xputc_type)(unsigned n, void *cookie);

void print_log(const char *fmt, ...)
{
	va_list args;
va_start(args, fmt);
	__xprintf(fmt, args, (xputc_type)print_char, 0);
	va_end(args);
}
#ifdef HIKEY_XAF_IPC_COMMENT_OUT
#define ISSPACE(c) (c == ' ' || c == 0x09 || c == 0x0A || c == 0x0D || c == 0)

char* dsp_om_trim_zero(char* str)
{
	char *str_begin = 0;
	char *str_end = 0;

	if (!str)
		return 0;

	str_begin = str;
	str_end = str + strlen(str);

	while (str_begin < str_end) {
		if (ISSPACE(*str_begin)) {
			*str_begin = 0;
			str_begin++;
		} else {
			break;
		}
	}
	while (str_begin < str_end) {
		if (ISSPACE(*str_end)) {
			*str_end = 0;
			str_end--;
		} else {
			break;
		}
	}

	return str_begin;
}

char * dsp_om_split_str(char* str, char** split_str)
{
	char *str_begin = 0;
	char *str_end = 0;

	if ((!str) || (!split_str)) {
		DSP_LOGE("input param is null\n");
		return str;
	}

	str_end = str + strlen(str);
	str_begin = dsp_om_trim_zero(str);

	if (str_begin == str_end) {
		DSP_LOGE("input str all space\n");
		return 0;
	}

	*split_str = dsp_om_trim_zero(strchr(str_begin, ' '));

	return str_begin;
}

#ifdef GJB_CHANGE
void send_msg_data_to_ap()
{

    struct hikey_msg_with_content hikey_msg;
    DSP_LOGE("%s\n", __func__);
    hikey_msg.msg_info.msg_id=HIKEY_AUDIO_DSP_AP_OM_CMD;
    hikey_msg.msg_info.msg_len=HIKEY_AP_DSP_MSG_MAX_LEN;
    strncpy(hikey_msg.msg_info.msg_content,"pcm_gain",HIKEY_AP_DSP_MSG_MAX_LEN);
    dsp_mailbox_write(&hikey_msg);
    DSP_LOGE("Exit %s\n", __func__);
}

void send_pcm_data_to_ap()
{
    struct hikey_ap_dsp_msg_body msg_info;
    DSP_LOGE("Enter %s\n", __func__);
    msg_info.msg_id = ID_XAF_DSP_TO_AP;
    msg_info.msg_len = sizeof(msg_info);
    msg_info.xf_dsp_msg.id= 0;
    msg_info.xf_dsp_msg.opcode = 0xc;
    msg_info.xf_dsp_msg.length = 0x400;
    msg_info.xf_dsp_msg.address = 0x8B432000;
    dsp_mailbox_write(&msg_info);
    DSP_LOGE("Exit %s\n", __func__);
}
#else
void send_msg_data_to_ap()
{
    xf_proxy_message_t hikey_msg;
    DSP_LOGE("%s\n", __func__);
    hikey_msg.id=HIKEY_AUDIO_DSP_AP_OM_CMD;
    hikey_msg.length=HIKEY_AP_DSP_MSG_MAX_LEN;
    strncpy(hikey_msg.address,"pcm_gain",HIKEY_AP_DSP_MSG_MAX_LEN);
    dsp_mailbox_write(&hikey_msg);
    DSP_LOGE("Exit %s\n", __func__);
}

void send_pcm_data_to_ap()
{
    xf_proxy_message_t msg_info;
    DSP_LOGE("Enter %s\n", __func__);
    msg_info.id = ID_XAF_DSP_TO_AP;
    msg_info.opcode = 0xc;
    msg_info.length = 0x400;
    msg_info.address = 0x8B432000;
    dsp_mailbox_write(&msg_info);
    DSP_LOGE("Exit %s\n", __func__);
}
#endif
static void dsp_om_read_mem(char *str)
{
	unsigned int addr  = 0;
	unsigned int val = 0;
	if (!str) {
		DSP_LOGE("str is null\n");
		return;
	}

	addr = strtoul(str, 0, 16);
	DSP_LOGD("str:%s addr:0x%x\n", str, addr);

	val = *(unsigned int*)addr;
    send_pcm_data_to_ap();
//send_msg_data_to_ap();
    dsp_ipc_send_irq_to_ap();
	DSP_LOGI("read addr:0x%x value:0x%x\n", addr, val);
	return;
}

static void dsp_om_write_mem(char *str)
{
	char* str_addr  = 0;
	char* str_val = 0;
	unsigned int addr  = 0;
	unsigned int val = 0;
	if (!str) {
	DSP_LOGE("str is null\n");
		return;
	}

	str_addr = dsp_om_split_str(str, &str_val);

	if(!str_addr || !str_val) {
		DSP_LOGE("str:%s str_addr:%s strValue:%s\n", str, str_addr ? str_addr : "null", str_val ? str_val : "null");
		return;
	}
	addr = strtoul(str_addr, 0, 16);
	val= strtoul(str_val, 0, 16);
	DSP_LOGI("str_addr:%s addr:%x str_val:%s val:%x\n", str_addr ? str_addr : "null", addr, str_val ? str_val : "null", val);

	*(unsigned int *)addr = val;

	return;

}

static void dsp_om_pcm_gain(char *str)
{
	char* str_addr  = 0;
	char* str_val = 0;
	unsigned int addr  = 0;
	unsigned int val = 0;
	if (!str) {
	DSP_LOGE("str is null\n");
		return;
	}
	str_addr = dsp_om_split_str(str, &str_val);
	if(!str_addr || !str_val) {
		DSP_LOGE("str:%s str_addr:%s strValue:%s\n", str, str_addr ? str_addr : "null", str_val ? str_val : "null");
		return;
	}
	addr = strtoul(str_addr, 0, 16);
	val= strtoul(str_val, 0, 16);
	DSP_LOGI("str_addr:%s addr:%x str_val:%s val:%x\n", str_addr ? str_addr : "null", addr, str_val ? str_val : "null", val);
    if(ReadData((char*)InpBuf, val) ){
        processAudio(OutBuf, InpBuf, (val/4));  
        if(WriteData((char*)OutBuf, val)) {
            MsgFlag = MSG_PROC; // indicate that the msg is processed.
            DSP_LOGI("PCM gain processed\n");
            send_msg_data_to_ap();
            dsp_ipc_send_irq_to_ap(); // Indicate data is ready  to pickup...  Maybe you need to send a msg to AP.
        }
        else {                
            DSP_LOGI("PCM gain Write error\n");
            MsgFlag = MSG_INCOMP;
            dsp_ipc_send_irq_to_ap();// Report error..
        }
    }
    else {
        DSP_LOGI("PCM gain Read error\n");
        MsgFlag = MSG_INCOMP;
        dsp_ipc_send_irq_to_ap();
    }
	return;
}
typedef void (*om_proc_func)(char *str);

struct om_proc_info {
	char *om_proc_name;
	om_proc_func func;
};

struct om_proc_info om_proc_table[] = {
	{"read_mem", dsp_om_read_mem},
	{"write_mem", dsp_om_write_mem},
	{"pcm_gain", dsp_om_pcm_gain},
};

om_proc_func dsp_om_get_func_by_name(char *name)
{
	unsigned int i = 0;
	unsigned int func_num = sizeof(om_proc_table) / sizeof(om_proc_table[0]);

	if (!name) {
		DSP_LOGE("name is null\n");
		return 0;
	}

	for (i = 0; i < func_num; i++)
		if (!strncmp((char *)om_proc_table[i].om_proc_name, name, strlen((char *)om_proc_table[i].om_proc_name)))
			return om_proc_table[i].func;

	return 0;
}

void dsp_om_func_proc(char *om_str, unsigned int str_len)
{
	char * cmd_name = 0;
	char * str_param = 0;
	om_proc_func proc_func = 0;

	cmd_name = dsp_om_split_str((char *)om_str, &str_param);

	DSP_LOGI("cmd_name:%s\n", cmd_name);
	proc_func = dsp_om_get_func_by_name(cmd_name);
	if (proc_func)
		proc_func(str_param);
	else
		DSP_LOGE("do not find func\n");
}
#endif

