#ifndef _AUDIO_HIFI_H
#define _AUDIO_HIFI_H

#include <linux/types.h>

struct misc_io_dump_buf_param {
	uint64_t user_buf;
	unsigned int clear;	/*clear current log buf */
	unsigned int buf_size;
};

struct misc_io_pcm_buf_param {
	uint64_t buf;
	uint32_t buf_size;
};

#define HIFI_DSP_MISC_DRIVER	"/dev/hifi_misc"

#define HIFI_MISC_IOCTL_ASYNCMSG		_IOWR('A', 0x70, struct misc_io_async_param)
#define HIFI_MISC_IOCTL_SYNCMSG			_IOW('A', 0x71, struct misc_io_sync_param)
#define HIFI_MISC_IOCTL_SENDDATA_SYNC		_IOW('A', 0x72, struct misc_io_senddata_sync_param)
#define HIFI_MISC_IOCTL_GET_PHYS		_IOWR('A', 0x73, struct misc_io_get_phys_param)
#define HIFI_MISC_IOCTL_TEST			_IOWR('A', 0x74, struct misc_io_senddata_sync_param)
#define HIFI_MISC_IOCTL_WRITE_PARAMS		_IOWR('A', 0x75, struct misc_io_sync_param)
#define HIFI_MISC_IOCTL_DUMP_HIFI		_IOWR('A', 0x76, struct misc_io_dump_buf_param)
#define HIFI_MISC_IOCTL_DUMP_CODEC		_IOWR('A', 0x77, struct misc_io_dump_buf_param)
#define HIFI_MISC_IOCTL_WAKEUP_THREAD		_IOW('A',  0x78, unsigned int)
#define HIFI_MISC_IOCTL_DISPLAY_MSG		_IOWR('A', 0x79, struct misc_io_dump_buf_param)
#define HIFI_MISC_IOCTL_WAKEUP_PCM_READ_THREAD	_IOW('A',  0x7a, unsigned int)
#define HIFI_MISC_IOCTL_PCM_GAIN		_IOW('A',  0x7b, struct misc_io_pcm_buf_param)
#define HIFI_MISC_IOCTL_XAF_IPC_MSG_SEND	_IOW('A',  0x7c, xf_proxy_msg_t)
#define HIFI_MISC_IOCTL_XAF_IPC_MSG_RECV	_IOR('A', 0x7d, xf_proxy_msg_t)

#ifdef CLT_VOICE
#define CLT_HIFI_MISC_IOCTL_SEND_VOICE		_IOWR('A', 0x90, struct misc_io_async_param)
#endif

#define HIFI_MISC_IOCTL_GET_VOICE_BSD_PARAM	_IOWR('A',  0x7c, unsigned int)

#define DRV_DSP_UART_TO_MEM_SIZE	0x7f000

#endif /* _AUDIO_HIFI_H */
