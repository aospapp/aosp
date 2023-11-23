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
#include <stdio.h>
#include <stdlib.h>

#include "xa_type_def.h"

/* ...debugging facility */
#include "xaf-utils-test.h"

mem_obj_t g_mem_obj;

void* mem_malloc(int size, int id)
{
    int index;
    void* heap_ptr = NULL;
	
    if(id == XAF_MEM_ID_DEV)
    {
        index = g_mem_obj.num_malloc_dev;
	if(index >= MEM_NUM_MEM_ALLOC-1)
	{
	    heap_ptr = NULL;
	}
	else
	{
	    heap_ptr = malloc(size);
            g_mem_obj.num_malloc_dev++;
            g_mem_obj.mem_dev[index].heap_ptr = heap_ptr;
            g_mem_obj.mem_dev[index].size = size;
            g_mem_obj.persi_mem_dev += size;
	}
    }
    else if(id == XAF_MEM_ID_COMP)
    {
        index = g_mem_obj.num_malloc_comp;
	if(index >= MEM_NUM_MEM_ALLOC-1)
	{
	    heap_ptr = NULL;
	}
	else
	{
	    heap_ptr = malloc(size);
            g_mem_obj.num_malloc_comp++;
            g_mem_obj.mem_comp[index].heap_ptr = heap_ptr;
            g_mem_obj.mem_comp[index].size = size;
            g_mem_obj.persi_mem_comp += size;
        }			
    }
    return heap_ptr;
}

int get_heap_ptr_index(void* p_heap, int id)
{
    int idx;
    
    idx = -1;
    if(id == XAF_MEM_ID_DEV)
    {
        for(idx = 0; idx < MEM_NUM_MEM_ALLOC; idx++)
        {
            if(g_mem_obj.mem_dev[idx].heap_ptr == p_heap)
                break;            
        }
    }
    
    else if(id == XAF_MEM_ID_COMP)
    {        
        for(idx = 0; idx < MEM_NUM_MEM_ALLOC; idx++)
        {
            if(g_mem_obj.mem_comp[idx].heap_ptr == p_heap)
                break;            
        }
    }
    return idx;
}

void mem_free(void * heap_ptr, int id)
{
    int index;
    int size;
    
    index = get_heap_ptr_index(heap_ptr, id);
    
    if (index != -1)
    {
        if(id == XAF_MEM_ID_DEV)
        {
	    size=g_mem_obj.mem_dev[index].size;
            g_mem_obj.mem_dev[index].size = 0;
            g_mem_obj.num_malloc_dev--;
            free(heap_ptr);
            g_mem_obj.mem_dev[index].heap_ptr = NULL;
        }
        else if(id == XAF_MEM_ID_COMP)
        {
	    size=g_mem_obj.mem_comp[index].size;
            g_mem_obj.mem_comp[index].size = 0;
            g_mem_obj.num_malloc_comp--;
            free(heap_ptr);
            g_mem_obj.mem_comp[index].heap_ptr = NULL;
        }
    }
    return;
}

int mem_get_alloc_size(mem_obj_t* pmem_handle, int id)
{
    int mem_size = 0;
    if(id == XAF_MEM_ID_DEV)
        mem_size =  pmem_handle->persi_mem_dev;
    else if(id == XAF_MEM_ID_COMP)
        mem_size = pmem_handle->persi_mem_comp;
    return mem_size;
}

void* mem_init()
{
    void* ptr;
    ptr = &g_mem_obj;
    return ptr;
}

void mem_exit()
{
    if((g_mem_obj.num_malloc_dev != 0)||(g_mem_obj.num_malloc_comp != 0))
    {
        fprintf(stdout,"Memory leaks\n");
    }
    return;
}
