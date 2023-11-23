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

#ifndef __DSP_DRIVER_MAILBOX_H__
#define __DSP_DRIVER_MAILBOX_H__

#include <stdint.h>

#define HIKEY_MSG_HEAD_PROTECT_WORD 0xffff1234
#define HIKEY_MSG_BODY_PROTECT_WORD 0xffff4321

#define	HIKEY_MSG_ID_AP_DSP_OM_CMD 0xDDCB
#define HIKEY_AUDIO_DSP_AP_OM_CMD   0xDDC9

typedef enum HIFI_MSG_ID_ {

	/*DTS command id from ap*/
	ID_AP_AUDIO_SET_DTS_ENABLE_CMD			= 0xDD36,
	ID_AP_AUDIO_SET_DTS_DEV_CMD			= 0xDD38,
	ID_AP_AUDIO_SET_DTS_GEQ_CMD			= 0xDD39,
	ID_AP_AUDIO_SET_DTS_GEQ_ENABLE_CMD		= 0xDD3B,

	/* APºÍHIFIµÄ¿ØÖÆÍâÖÃ¶ú»úHIFI codecœ»»¥ÏûÏ¢ */
	ID_AP_AUDIO_SET_EXCODEC_ENABLE_CMD		= 0xDD3D,

	/* Voice Record */
	ID_AP_HIFI_VOICE_RECORD_START_CMD		= 0xDD40,
	ID_AP_HIFI_VOICE_RECORD_STOP_CMD		= 0xDD41,

	/* voicePP MSG_ID */
	ID_AP_VOICEPP_START_REQ				= 0xDD42,						/* Æô¶¯VOICEPPÍš»° */
	ID_VOICEPP_MSG_START				= ID_AP_VOICEPP_START_REQ,
	ID_VOICEPP_AP_START_CNF				= 0xDD43,
	ID_AP_VOICEPP_STOP_REQ				= 0xDD44,						/* ÖÕÖ¹VOICEPPÍš»° */
	ID_VOICEPP_AP_STOP_CNF				= 0xDD45,
	ID_VOICEPP_MSG_END				= 0xDD4A,

	ID_AP_AUDIO_PLAY_START_REQ			= 0xDD51,/* APÆô¶¯Hifi audio player requestÃüÁî */
	ID_AUDIO_AP_PLAY_START_CNF			= 0xDD52,/* HifiÆô¶¯audio playerºó»ØžŽAP confirmÃüÁî */
	ID_AP_AUDIO_PLAY_PAUSE_REQ			= 0xDD53,/* APÍ£Ö¹Hifi audio player requestÃüÁî */
	ID_AUDIO_AP_PLAY_PAUSE_CNF			= 0xDD54,/* HifiÍ£Ö¹audio playerºó»ØžŽAP confirmÃüÁî */
	ID_AUDIO_AP_PLAY_DONE_IND			= 0xDD56,/* HifiÍšÖªAP audio playerÒ»¿éÊýŸÝ²¥·ÅÍê±Ï»òÕß²¥·ÅÖÐ¶Ïindication */
	ID_AP_AUDIO_PLAY_UPDATE_BUF_CMD			= 0xDD57,/* APÍšÖªHifiÐÂÊýŸÝ¿éžüÐÂcommand */
	ID_AP_AUDIO_PLAY_QUERY_TIME_REQ			= 0xDD59,/* AP²éÑ¯Hifi audio player²¥·Åœø¶ÈrequestÃüÁî */
	ID_AP_AUDIO_PLAY_WAKEUPTHREAD_REQ		= 0xDD5A,
	ID_AUDIO_AP_PLAY_QUERY_TIME_CNF			= 0xDD60,/* Hifi»ØžŽAP audio player²¥·Åœø¶ÈconfirmÃüÁî */
	ID_AP_AUDIO_PLAY_QUERY_STATUS_REQ		= 0xDD61,/* AP²éÑ¯Hifi audio player²¥·Å×ŽÌ¬requestÃüÁî */
	ID_AUDIO_AP_PLAY_QUERY_STATUS_CNF		= 0xDD62,/* Hifi»ØžŽAP audio player²¥·Å×ŽÌ¬confirmÃüÁî */
	ID_AP_AUDIO_PLAY_SEEK_REQ			= 0xDD63,/* AP seek Hifi audio playerµœÄ³Ò»Î»ÖÃrequestÃüÁî */
	ID_AUDIO_AP_PLAY_SEEK_CNF			= 0xDD64,/* Hifi»ØžŽAP seekœá¹ûconfirmÃüÁî */
	ID_AP_AUDIO_PLAY_SET_VOL_CMD			= 0xDD70,/* APÉèÖÃÒôÁ¿ÃüÁî */
	ID_AP_AUDIO_RECORD_PCM_HOOK_CMD			= 0xDD7A,/* AP ÍšÖªHIFI¿ªÊŒ×¥È¡PCMÊýŸÝ */
	ID_AUDIO_AP_UPDATE_PCM_BUFF_CMD			= 0xDD7C,
	ID_AP_AUDIO_DYN_EFFECT_GET_PARAM		= 0xDD7D,
	ID_AP_AUDIO_DYN_EFFECT_GET_PARAM_CNF		= 0xDD7E,
	ID_AP_AUDIO_DYN_EFFECT_TRIGGER			= 0xDD7F,
	/* enhance msgid between ap and hifi */
	ID_AP_HIFI_ENHANCE_START_REQ			= 0xDD81,
	ID_HIFI_AP_ENHANCE_START_CNF			= 0xDD82,
	ID_AP_HIFI_ENHANCE_STOP_REQ			= 0xDD83,
	ID_HIFI_AP_ENHANCE_STOP_CNF			= 0xDD84,
	ID_AP_HIFI_ENHANCE_SET_DEVICE_REQ		= 0xDD85,
	ID_HIFI_AP_ENHANCE_SET_DEVICE_CNF		= 0xDD86,

	/* audio enhance msgid between ap and hifi */
	ID_AP_AUDIO_ENHANCE_SET_DEVICE_IND		= 0xDD91,
	ID_AP_AUDIO_MLIB_SET_PARA_IND			= 0xDD92,
	ID_AP_AUDIO_CMD_SET_SOURCE_CMD			= 0xDD95,
	ID_AP_AUDIO_CMD_SET_DEVICE_CMD			= 0xDD96,
	ID_AP_AUDIO_CMD_SET_MODE_CMD			= 0xDD97,
	ID_AP_AUDIO_CMD_SET_ANGLE_CMD			= 0xDD99,

	/* for 3mic */
	ID_AP_AUDIO_ROUTING_COMPLETE_REQ		= 0xDDC0,/*AP ÍšÖªHIFI 3Mic/4Mic ÍšÂ·ÒÑœšÁ¢*/
	ID_AUDIO_AP_DP_CLK_EN_IND			= 0xDDC1,/* HIFI ÍšÖªAºËŽò¿ª»ò¹Ø±ÕCodec DPÊ±ÖÓ */
	ID_AP_AUDIO_DP_CLK_STATE_IND			= 0xDDC2,/* AºËÍšÖªHIFI £¬Codec DPÊ±ÖÓ×ŽÌ¬( Žò¿ª»ò¹Ø±Õ) */
	ID_AUDIO_AP_OM_DUMP_CMD				= 0xDDC3,/* HIFI ÍšÖªAºËdumpÈÕÖŸ */
	ID_AUDIO_AP_FADE_OUT_REQ			= 0xDDC4,/* HIFIÍšÖªAP×öµ­³öŽŠÀí */
	ID_AP_AUDIO_FADE_OUT_IND			= 0xDDC5,/* APÍšÖªHIFIµ­³öÍê±Ï */

	ID_AUDIO_AP_OM_CMD				= 0xDDC9,
	ID_AP_AUDIO_STR_CMD				= 0xDDCB,/* APžøHIFI·¢ËÍÒ»žö×Ö·ûŽ®£¬ŸßÌåº¬ÒåhifiÖÐœâÎö */
	ID_AUDIO_AP_VOICE_BSD_PARAM_CMD			= 0xDDCC,/* VOICE BSD ²ÎÊýÉÏ±š */

	ID_AP_ENABLE_MODEM_LOOP_REQ			= 0xDDCD,/* the audio hal notify HIFI to start/stop  MODEM LOOP*/
	ID_AP_HIFI_REQUEST_VOICE_PARA_REQ		= 0xDF00, /*AP REQUEST VOICE MSG*/
	ID_HIFI_AP_REQUEST_VOICE_PARA_CNF		= 0xDF01, /*HIFI REPLAY VOICE MSG*/

	/* XAF message IDs */
    ID_XAF_AP_TO_DSP                    = 0xDF10,
    ID_XAF_DSP_TO_AP                    = 0xDF11,
} HIFI_MSG_ID;

#define HIKEY_AP2DSP_MSG_QUEUE_ADDR HIFI_HIKEY_SHARE_MEM_ADDR

#define HIKEY_AP2DSP_MSG_QUEUE_SIZE 0x1800
#define HIKEY_DSP2AP_MSG_QUEUE_ADDR (HIKEY_AP2DSP_MSG_QUEUE_ADDR + HIKEY_AP2DSP_MSG_QUEUE_SIZE)
#define HIKEY_DSP2AP_MSG_QUEUE_SIZE 0x1800

#define HIKEY_AP_DSP_MSG_MAX_LEN 100
/////////////////////////////////////////////////////////////////////
//    XAF STRUCTURES                                               //
/////////////////////////////////////////////////////////////////////
#ifdef GJB_CHANGE
/* ...command/response message */
typedef struct xf_proxy_msg {
	/* ...session ID */
	uint32_t                 id;

	/* ...proxy API command/reponse code */
	uint32_t                 opcode;

	/* ...length of attached buffer */
	uint32_t                 length;

	/* ...physical address of message buffer */
	uint64_t                 address;
	uint64_t                 v_address;

}	__attribute__((__packed__)) xf_proxy_msg_t;

struct hikey_ap_dsp_msg_head {
	unsigned int head_protect_word;
	unsigned int msg_num;
	unsigned int read_pos;
	unsigned int write_pos;
};

/*struct hikey_ap_dsp_msg_body {
	unsigned short msg_id;
	unsigned short msg_len;
	char msg_content[0];
};*/

struct hikey_ap_dsp_msg_body {
	unsigned short msg_id;
	unsigned short msg_len;
    union {
	    char msg_content[0];
        struct xf_proxy_msg xf_dsp_msg;
    };
};
struct hikey_msg_with_content {
	struct hikey_ap_dsp_msg_body msg_info;
	char msg_content[HIKEY_AP_DSP_MSG_MAX_LEN];
};
void dsp_init_share_mem(char *share_mem_addr, unsigned int share_mem_size);
int dsp_mailbox_read(struct hikey_msg_with_content *hikey_msg);
void dsp_msg_process(struct hikey_msg_with_content *hikey_msg);
#else
#endif
#endif
