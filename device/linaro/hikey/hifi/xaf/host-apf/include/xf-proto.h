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

#ifndef __XF_H
#error "xf-proto.h mustn't be included directly"
#endif

/*******************************************************************************
 * Forward types declarations
 ******************************************************************************/

/* ...component string identifier */
typedef const char             *xf_id_t;

/* ...handle to proxy data */
typedef struct xf_proxy         xf_proxy_t;

/* ...handle to component data */
typedef struct xf_handle        xf_handle_t;

/* ...buffer pool */
typedef struct xf_pool          xf_pool_t;

/* ...individual buffer from pool */
typedef struct xf_buffer        xf_buffer_t;

/* ...buffer pool type */
typedef u32                     xf_pool_type_t;

/* ...user-message */
typedef struct xf_user_msg      xf_user_msg_t;

/* ...proxy-message */
typedef struct xf_proxy_msg     xf_proxy_msg_t;

/* ...response callback */
typedef void (*xf_response_cb)(xf_handle_t *h, xf_user_msg_t *msg);

typedef void* xaf_mem_malloc_fxn_t(s32 size, s32 id);
typedef void  xaf_mem_free_fxn_t(void* ptr, s32 id);

/*******************************************************************************
 * High-level API functions
 ******************************************************************************/

/* ...component operations */
extern int      xf_open(xf_proxy_t *proxy, xf_handle_t *handle, xf_id_t id, u32 core, xf_response_cb cb);
extern void     xf_close(xf_handle_t *handle);
extern int      xf_command(xf_handle_t *handle, u32 dst, u32 opcode, void *buf, u32 length);
extern int      xf_route(xf_handle_t *src, u32 s_port, xf_handle_t *dst, u32 d_port, u32 num, u32 size, u32 align);
extern int      xf_unroute(xf_handle_t *src, u32 s_port);

/* ...shared buffers operations */
extern int      xf_pool_alloc(xf_proxy_t *proxy, u32 number, u32 length, xf_pool_type_t type, xf_pool_t **pool, s32 id, 
		xaf_mem_malloc_fxn_t, xaf_mem_free_fxn_t);
extern void     xf_pool_free(xf_pool_t *pool, s32 id, xaf_mem_free_fxn_t);
extern xf_buffer_t * xf_buffer_get(xf_pool_t *pool);
extern void     xf_buffer_put(xf_buffer_t *buffer);

/* ...proxy operations */
extern int      xf_proxy_init(xf_proxy_t *proxy, u32 core, void *p_shmem);
extern void     xf_proxy_close(xf_proxy_t *proxy);

