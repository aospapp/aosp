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
#error  "xf-proxy.h mustn't be included directly"
#endif

/*******************************************************************************
 * User-message description - move from here to API - tbd
 ******************************************************************************/

/* ...need that at all? hope no */
struct xf_user_msg
{
    /* ...source component specification */
    u32             id;
    
    /* ...message opcode */
    u32             opcode;
    
    /* ...buffer length */
    u32             length;
    
    /* ...buffer pointer */
    void           *buffer;
};

/* ...proxy message - bad placing of that thing here - tbd */
struct xf_proxy_msg
{
    /* ...session-id field */
    uint32_t             id;
    
    /* ...message opcode */
    uint32_t             opcode;
    
    /* ...buffer length */
    uint32_t             length;
    
    /* ...buffer pointer */
    uint64_t             address;
    uint64_t         v_address;

}   __attribute__((__packed__));

typedef struct xf_proxy_msg_driv
{
    /* ...session ID */
    uint32_t                 id;

    /* ...proxy API command/reponse code */
    uint32_t                 opcode;

    /* ...length of attached buffer */
    uint32_t                 length;

    /* ...physical address of message buffer */
    uint64_t                 address;
    uint64_t                 v_address;

}__attribute__((__packed__)) xf_proxy_message_driv_t;
/*******************************************************************************
 * Buffer pools
 ******************************************************************************/

/* ...buffer pool type */
enum xf_pool_type
{
    XF_POOL_AUX = 0,
    XF_POOL_INPUT = 1,
    XF_POOL_OUTPUT = 2
};

/* ...buffer link pointer */
typedef union xf_buffer_link
{
    /* ...pointer to next free buffer in a pool (for free buffer) */
    xf_buffer_t        *next;
    
    /* ...reference to a buffer pool (for allocated buffer) */
    xf_pool_t          *pool;

}   xf_buffer_link_t;

/* ...buffer descriptor */
struct xf_buffer
{
    /* ...virtual address of contiguous buffer */
    void               *address;

    /* ...link pointer */
    xf_buffer_link_t    link;
};

/* ...buffer pool */
struct xf_pool
{
    /* ...reference to proxy data */
    xf_proxy_t         *proxy;

    /* ...length of individual buffer in a pool */
    u32                 length;
    
    /* ...number of buffers in a pool */
    u32                 number;

    /* ...pointer to pool memory */
    void               *p;
    
    /* ...pointer to first free buffer in a pool */
    xf_buffer_t        *free;

    /* ...individual buffers */
    xf_buffer_t         buffer[0];
};

/* ...accessor to buffer data */
static inline void * xf_buffer_data(xf_buffer_t *buffer)
{
    return buffer->address;
}

/* ...length of buffer data */
static inline size_t xf_buffer_length(xf_buffer_t *buffer)
{
    return buffer->link.pool->length;
}

/*******************************************************************************
 * Proxy handle definition
 ******************************************************************************/

/* ...free clients list */
typedef union xf_proxy_cmap_link
{
    /* ...index of next free client in the list */
    u32                     next;
    
    /* ...pointer to allocated component handle */
    xf_handle_t            *handle;

}   xf_proxy_cmap_link_t;

/* ...proxy data structure */
struct xf_proxy
{
    /* ...platform-specific IPC data */
    xf_proxy_ipc_data_t     ipc;
    
    /* ...auxiliary buffer pool for clients */
    xf_pool_t              *aux;

    /* ...global proxy lock */
    xf_lock_t               lock;

    /* ...proxy thread handle */
    xf_thread_t             thread;

    /* ...proxy identifier (core of remote DSP hosting SHMEM interface) */
    u32                     core;

    /* ...client association map */
    xf_proxy_cmap_link_t    cmap[XF_CFG_PROXY_MAX_CLIENTS];
};

/*******************************************************************************
 * Auxiliary proxy helpers
 ******************************************************************************/

/* ...get proxy identifier */
static inline u32 xf_proxy_id(xf_proxy_t *proxy)
{
    return proxy->core;
}

/* ...lock proxy data */
static inline void xf_proxy_lock(xf_proxy_t *proxy)
{
    __xf_lock(&proxy->lock);
}

/* ...unlock proxy data */
static inline void xf_proxy_unlock(xf_proxy_t *proxy)
{
    __xf_unlock(&proxy->lock);
}

/* ...translate proxy shared address into local virtual address */
static inline void * xf_proxy_a2b(xf_proxy_t *proxy, u32 address)
{
    return xf_ipc_a2b(&proxy->ipc, address);
}

/* ...translate local virtual address into shared proxy address */
static inline u32 xf_proxy_b2a(xf_proxy_t *proxy, void *b)
{
    return xf_ipc_b2a(&proxy->ipc, b);
}

/* ...submit asynchronous response message */
static inline int xf_proxy_response_put(xf_proxy_t *proxy, xf_proxy_msg_t *msg)
{
    return xf_proxy_ipc_response_put(&proxy->ipc, msg);
}

/* ...retrieve asynchronous response message */
static inline int xf_proxy_response_get(xf_proxy_t *proxy, xf_proxy_msg_t *msg)
{
    return xf_proxy_ipc_response_get(&proxy->ipc, msg);
}

/*******************************************************************************
 * Component handle definition
 ******************************************************************************/

struct xf_handle
{
    /* ...platform-specific IPC data */
    xf_ipc_data_t           ipc;
    
    /* ...reference to proxy data */
    xf_proxy_t             *proxy;
    
    /* ...component lock */
    xf_lock_t               lock;
 
    /* ...auxiliary control buffer for control transactions */
    xf_buffer_t            *aux;

    /* ...global client-id of the component */
    u32                     id;

    /* ...local client number (think about merging into "id" field - tbd) */
    u32                     client;
    
    /* ...response processing hook */
    xf_response_cb          response;
};

/*******************************************************************************
 * Auxiliary component helpers
 ******************************************************************************/

/* ...component client-id (global scope) */
static inline u32 xf_handle_id(xf_handle_t *handle)
{
    return handle->id;
}

/* ...pointer to auxiliary buffer */
static inline void * xf_handle_aux(xf_handle_t *handle)
{
    return xf_buffer_data(handle->aux);
}

/* ...acquire component lock */
static inline void xf_lock(xf_handle_t *handle)
{
    __xf_lock(&handle->lock);
}

/* ...release component lock */
static inline void xf_unlock(xf_handle_t *handle)
{
    __xf_unlock(&handle->lock);
}

/* ...put asynchronous response into local IPC */
static inline int xf_response_put(xf_handle_t *handle, xf_user_msg_t *msg)
{
    return xf_ipc_response_put(&handle->ipc, msg);
}

/* ...get asynchronous response from local IPC */
static inline int xf_response_get(xf_handle_t *handle, xf_user_msg_t *msg)
{
    return xf_ipc_response_get(&handle->ipc, msg);
}
