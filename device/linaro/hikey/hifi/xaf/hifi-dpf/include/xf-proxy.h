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

/*******************************************************************************
 * xf-proxy.h
 *
 * Proxy commmand/response messages
 *
 *******************************************************************************/

#ifndef __XF_H
#error "xf-proxy.h mustn't be included directly"
#endif

/*******************************************************************************
 * Types definitions
 ******************************************************************************/
#ifdef XAF_ENABLE_NON_HIKEY
/* ...command/response message */
typedef struct xf_proxy_message
{
    /* ...session ID */
    uint32_t                 session_id;

    /* ...proxy API command/reponse code */
    uint32_t                 opcode;

    /* ...length of attached buffer */
    uint32_t                 length;

    /* ...physical address of message buffer */
    uint64_t                 address;
    uint64_t                 v_address;

}   __attribute__((__packed__)) xf_proxy_message_t;
#else
/* ...command/response message */
typedef struct xf_proxy_message
{
    /* ...session ID */
    uint32_t                 session_id;

    /* ...proxy API command/reponse code */
    uint32_t                 opcode;

    /* ...length of attached buffer */
    uint32_t                 length;

    /* ...physical address of message buffer */
    uint64_t                 address;
    uint64_t                 v_address;

}   __attribute__((__packed__)) xf_proxy_message_t;
#endif
/*******************************************************************************
 * Ring buffer support
 ******************************************************************************/

/* ...total length of shared memory queue (for commands and responses) */
#define XF_PROXY_MESSAGE_QUEUE_LENGTH   (1 << 8)

/* ...index mask */
#define XF_PROXY_MESSAGE_QUEUE_MASK     0xFF

/* ...ring-buffer index */
#define __XF_QUEUE_IDX(idx, counter)    \
    (((idx) & XF_PROXY_MESSAGE_QUEUE_MASK) | ((counter) << 16))

/* ...retrieve ring-buffer index */
#define XF_QUEUE_IDX(idx)               \
    ((idx) & XF_PROXY_MESSAGE_QUEUE_MASK)

/* ...increment ring-buffer index */
#define XF_QUEUE_ADVANCE_IDX(idx)       \
    (((idx) + 0x10001) & (0xFFFF0000 | XF_PROXY_MESSAGE_QUEUE_MASK))

/* ...test if ring buffer is empty */
#define XF_QUEUE_EMPTY(read, write)     \
    ((read) == (write))

/* ...test if ring buffer is full */
#define XF_QUEUE_FULL(read, write)      \
    ((write) == (read) + (XF_PROXY_MESSAGE_QUEUE_LENGTH << 16))
