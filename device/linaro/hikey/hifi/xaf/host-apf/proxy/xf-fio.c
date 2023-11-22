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

#define MODULE_TAG                      FIO

/*******************************************************************************
 * Includes
 ******************************************************************************/

#include "xf.h"
#include <sys/ioctl.h>
#include <sys/mman.h>

/*******************************************************************************
 * Tracing configuration
 ******************************************************************************/

TRACE_TAG(INIT, 1);
TRACE_TAG(CMD, 1);
TRACE_TAG(RSP, 1);

/*******************************************************************************
 * Local constants - tbd
 ******************************************************************************/

/* ...proxy setup ioctl */
#define XF_PROXY_SETUP_IOCTL            _IO('P', 0x0)

/* ...proxy close ioctl */
#define XF_PROXY_CLOSE_IOCTL            _IO('P', 0x1)

#define HIFI_DSP_MISC_DRIVER "/dev/hifi_misc"
#ifndef GJB_COMMENT
#define HIFI_MISC_IOCTL_XAF_IPC_MSG_SEND _IOW('A',  0x7c, xf_proxy_message_driv_t)
#define HIFI_MISC_IOCTL_XAF_IPC_MSG_RECV _IOR('A', 0x7d, xf_proxy_message_driv_t)
#define HIFI_MISC_IOCTL_XAF_IPC_VMSG_PTR _IOR('A', 0x7e, xf_proxy_message_driv_t)
#endif
//u8 remote_ipc_pool[XF_CFG_REMOTE_IPC_POOL_SIZE];
/*******************************************************************************
 * Internal IPC API implementation
 ******************************************************************************/

/* ...pass command to remote DSP */
int xf_ipc_send(xf_proxy_ipc_data_t *ipc, xf_proxy_msg_t *msg, void *b)
{
    /* ...unused arg */
    (void) b;

    int     fd = ipc->fd;
    int ret;
#ifdef GJB_COMMENT
    TRACE(CMD, _b("C[%08x]:(%x,%08x,%u)"), msg->id, msg->opcode, msg->address, msg->length);    

    /* ...pass message to kernel driver */
    XF_CHK_ERR(write(fd, msg, sizeof(*msg)) == sizeof(*msg), -errno);
#else
	ret = ioctl(fd, HIFI_MISC_IOCTL_XAF_IPC_MSG_SEND, msg);// GJB:-Verify th return value with driver implementation.
#endif

    /* ...communication mutex is still locked! */
    return 0;
}

/* ...wait for response availability */
int xf_ipc_wait(xf_proxy_ipc_data_t *ipc, u32 timeout)
{
    int             fd = ipc->fd;
    fd_set          rfds;
    struct timeval  tv;
    
    /* ...specify waiting set */
    FD_ZERO(&rfds);
    FD_SET(fd, &rfds);
    
    /* ...set timeout value if given */
    (timeout ? tv.tv_sec = timeout / 1000, tv.tv_usec = (timeout % 1000) * 1000 : 0);
    
    /* ...wait until there is a data in file */
//    XF_CHK_ERR(select(fd + 1, &rfds, NULL, NULL, (timeout ? &tv : NULL)) >= 0, -errno);
	select(fd+1,&rfds,NULL,NULL,(timeout? &tv: NULL));
    
    /* ...check if descriptor is set */
    return (FD_ISSET(fd, &rfds) ? 0 : -ETIMEDOUT);
}

/* ...read response from proxy */
int xf_ipc_recv(xf_proxy_ipc_data_t *ipc, xf_proxy_msg_t *msg, void **buffer)
{
    int     fd = ipc->fd;
    int     r;
    xf_proxy_msg_t temp;
#ifdef GJB_COMMENT
    /* ...get message header from file */
    if ((r = read(fd, msg, sizeof(*msg))) == sizeof(*msg))
    {
        TRACE(RSP, _b("R[%08x]:(%x,%u,%08x)"), msg->id, msg->opcode, msg->length, msg->address);

        /* ...translate shared address into local pointer */
        XF_CHK_ERR((*buffer = xf_ipc_a2b(ipc, msg->address)) != (void *)-1, -EBADFD);

        /* ...return positive result indicating the message has been received */
        return sizeof(*msg);
    }
#else
    if ((r = ioctl(fd, HIFI_MISC_IOCTL_XAF_IPC_MSG_RECV, &temp)) == sizeof(temp))
    {
        msg->id = temp.id;
        msg->opcode = temp.opcode;
        msg->length = temp.length;
        *buffer = xf_ipc_a2b(ipc, temp.address);
        /* ...translate shared address into local pointer */
        XF_CHK_ERR((*buffer = xf_ipc_a2b(ipc, temp.address)) != (void *)-1, -EBADFD);
        msg->address = temp.address;
        return sizeof(*msg);
    }
#endif
    else
    {
        /* ...if no response is available, return 0 result */
        return XF_CHK_API(errno == EAGAIN ? 0 : -errno);
    }
}

/*******************************************************************************
 * Internal API functions implementation
 ******************************************************************************/

/* ...open proxy interface on proper DSP partition */
int xf_ipc_open(xf_proxy_ipc_data_t *ipc, u32 core, void *p_shmem)
{
    //XF_CHK_ERR((p_shmem != NULL), -errno);
    //size_t xf_cfg_remote_ipc_pool_size = *(size_t *)p_shmem;//user configured shmem pool size: minimum 256 KB
    /* ...unused arg */
    (void) p_shmem;
#ifdef GJB_COMMENT	
    /* ...open file handle */
    XF_CHK_ERR((ipc->fd = open("/dev/xtensa-proxy", O_RDWR)) >= 0, -errno);

    /* ...pass shread memory core for this proxy instance */
    XF_CHK_ERR(ioctl(ipc->fd, XF_PROXY_SETUP_IOCTL, core) >= 0, -errno);    
#else
	XF_CHK_ERR((ipc->fd = open(HIFI_DSP_MISC_DRIVER, O_RDWR,0)) >= 0, -errno);
#endif
    /* ...create pipe for asynchronous response delivery */
    XF_CHK_ERR(pipe(ipc->pipe) == 0, -errno);

    /* ...map entire shared memory region (not too good - tbd) */
//	ipc->shmem = remote_ipc_pool;
//	ioctl(ipc->fd, HIFI_MISC_IOCTL_XAF_IPC_VMSG_PTR, ipc->shmem);
#if 1
    //allocate 256 KB constant size
    XF_CHK_ERR((ipc->shmem = mmap(NULL, XF_CFG_REMOTE_IPC_POOL_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, ipc->fd, 0)) != MAP_FAILED, -errno);
#else
    XF_CHK_ERR((ipc->shmem = mmap(NULL, xf_cfg_remote_ipc_pool_size, PROT_READ | PROT_WRITE, MAP_SHARED, ipc->fd, 0)) != MAP_FAILED, -errno);
#endif
    TRACE(INIT, _b("proxy-%u interface opened"), core); 
    return 0;
}

/* ...close proxy handle */
void xf_ipc_close(xf_proxy_ipc_data_t *ipc, u32 core)
{
    /* ...unmap shared memory region */
//    (void)munmap(ipc->shmem, XF_CFG_REMOTE_IPC_POOL_SIZE);

    /* ...close asynchronous response delivery pipe */
    close(ipc->pipe[0]), close(ipc->pipe[1]);
    
    /* ...close proxy file handle */
    close(ipc->fd);

    TRACE(INIT, _b("proxy-%u interface closed"), core);
}

