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
 * xf-mm.h
 *
 * Generic dynamic memory manager (based on rb-tree index)
 *
 *******************************************************************************/

#ifndef __XF_H
#error "xf-mem.h mustn't be included directly"
#endif

/*******************************************************************************
 * Includes
 ******************************************************************************/

/* ...red-black trees library */
#include "lib/rbtree.h"

/*******************************************************************************
 * Cache-line aligned types
 ******************************************************************************/

/* ...proper cache-line alignment */
#define XF_ALIGNED(size)                            \
    (((size) + XF_PROXY_ALIGNMENT - 1) & ~(XF_PROXY_ALIGNMENT - 1))

/* ...cache-line aligned type identifier */
#define XF_ALIGNED_TYPE(type)                       \
    __xf_aligned_##type

/* ...definition of cache-line aligned type */
#define XF_ALIGNED_TYPEDEF(type, name)              \
typedef union                                       \
{                                                   \
    /* ...item of original type */                  \
    type        __obj;                              \
                                                    \
    /* ...padding to cache-line */                  \
    u8          __pad[XF_ALIGNED(sizeof(type))];    \
                                                    \
}   XF_ALIGNED_TYPE(type), name __xf_shmem__

/* ...accessor to original type */
#define XF_ALIGNED_OBJ(p)                           \
    (&(p)->__obj)

#define XF_IS_ALIGNED(p)                            \
    (((u32)(p) & (XF_PROXY_ALIGNMENT - 1)) == 0)

/*******************************************************************************
 * Memory pool description
 ******************************************************************************/

/* ...memory allocator data */
typedef struct xf_mm_pool
{
    /* ...free blocks map sorted by block length */
    rb_tree_t       l_map;
    
    /* ...free blocks map sorted by address of the block */
    rb_tree_t       a_map;

    /* ...address of memory pool (32-bytes aligned at least); need that? - tbd */
    void           *addr;

    /* ...length of the pool (multiple of descriptor size); need that? - tbd */
    u32             size;    
    
}   xf_mm_pool_t;

/* ...descriptor of free memory block */
typedef struct xf_mm_block
{
    /* ...rb-tree node in a block-length map */
    rb_node_t       l_node;
    
    /* ...rb-tree node in a block-address map */
    rb_node_t       a_node;
    
}   xf_mm_block_t;

/* ...properly aligned allocation unit */
typedef u8 xf_mm_item[xf_next_power_of_two(sizeof(xf_mm_block_t))];

/* ...macro to assure proper alignment of dynamically allocated data */
#define XF_MM(size)         (((size) + sizeof(xf_mm_item) - 1) & ~(sizeof(xf_mm_item) - 1))

/* ...check if memory is properly aligned */
#define XF_MM_ALIGNED(size) (!((size) & (sizeof(xf_mm_item) - 1)))

/* ...alignement definition */
#define __xf_mm__  __attribute__((__aligned__(sizeof(xf_mm_item))))
    
/*******************************************************************************
 * Dynamically allocated buffer
 ******************************************************************************/

/* ...memory allocation metadata */
typedef struct xf_mm_buffer
{
    /* ...allocation address */
    void           *addr;
    
    /* ...length */
    u32             size;
    
}   __xf_mm__ xf_mm_buffer_t;

/*******************************************************************************
 * API functions
 ******************************************************************************/

/* ...pool initialization */
extern int      xf_mm_init(xf_mm_pool_t *pool, void *addr, u32 size);

/* ...block allocation */
extern void *   xf_mm_alloc(xf_mm_pool_t *pool, u32 size);

/* ...block deallocation */
extern void     xf_mm_free(xf_mm_pool_t *pool, void *addr, u32 size);
