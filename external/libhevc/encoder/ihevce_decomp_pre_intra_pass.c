/******************************************************************************
 *
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *****************************************************************************
 * Originally developed and contributed by Ittiam Systems Pvt. Ltd, Bangalore
*/

/*!
******************************************************************************
* \file ihevce_decomp_pre_intra_pass.c
*
* \brief
*    This file contains definitions related to frame decomposition done during
*    pre intra processing
*
* \date
*    19/02/2013
*
* \author
*    Ittiam
*
* List of Functions
*    ihevce_intra_populate_mode_bits_cost()
*    ihevce_8x8_sad_computer()
*    ihevce_4x4_sad_computer()
*    ihevce_ed_4x4_find_best_modes()
*    ihevce_ed_calc_4x4_blk()
*    ihevce_ed_calc_8x8_blk()
*    ihevce_ed_calc_incomplete_ctb()
*    ihevce_cu_level_qp_mod()
*    ihevce_ed_calc_ctb()
*    ihevce_ed_frame_init()
*    ihevce_scale_by_2()
*    ihevce_decomp_pre_intra_process_row()
*    ihevce_decomp_pre_intra_process()
*    ihevce_decomp_pre_intra_get_num_mem_recs()
*    ihevce_decomp_pre_intra_get_mem_recs()
*    ihevce_decomp_pre_intra_init()
*    ihevce_decomp_pre_intra_frame_init()
*    ihevce_merge_sort()
*    ihevce_decomp_pre_intra_curr_frame_pre_intra_deinit()
*
******************************************************************************
*/

/*****************************************************************************/
/* File Includes                                                             */
/*****************************************************************************/
/* System include files */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <assert.h>
#include <stdarg.h>
#include <math.h>
#include <limits.h>

/* User include files */
#include "ihevc_typedefs.h"
#include "itt_video_api.h"
#include "ihevce_api.h"

#include "rc_cntrl_param.h"
#include "rc_frame_info_collector.h"
#include "rc_look_ahead_params.h"

#include "ihevc_defs.h"
#include "ihevc_debug.h"
#include "ihevc_structs.h"
#include "ihevc_platform_macros.h"
#include "ihevc_deblk.h"
#include "ihevc_itrans_recon.h"
#include "ihevc_chroma_itrans_recon.h"
#include "ihevc_chroma_intra_pred.h"
#include "ihevc_intra_pred.h"
#include "ihevc_inter_pred.h"
#include "ihevc_mem_fns.h"
#include "ihevc_padding.h"
#include "ihevc_weighted_pred.h"
#include "ihevc_sao.h"
#include "ihevc_resi_trans.h"
#include "ihevc_quant_iquant_ssd.h"
#include "ihevc_cabac_tables.h"

#include "ihevce_defs.h"
#include "ihevce_hle_interface.h"
#include "ihevce_lap_enc_structs.h"
#include "ihevce_multi_thrd_structs.h"
#include "ihevce_multi_thrd_funcs.h"
#include "ihevce_me_common_defs.h"
#include "ihevce_had_satd.h"
#include "ihevce_error_codes.h"
#include "ihevce_bitstream.h"
#include "ihevce_cabac.h"
#include "ihevce_rdoq_macros.h"
#include "ihevce_function_selector.h"
#include "ihevce_enc_structs.h"
#include "ihevce_entropy_structs.h"
#include "ihevce_cmn_utils_instr_set_router.h"
#include "ihevce_ipe_instr_set_router.h"
#include "ihevce_decomp_pre_intra_structs.h"
#include "ihevce_decomp_pre_intra_pass.h"
#include "ihevce_enc_loop_structs.h"
#include "hme_datatype.h"
#include "hme_interface.h"
#include "hme_common_defs.h"
#include "ihevce_global_tables.h"

/*****************************************************************************/
/* Typedefs                                                                  */
/*****************************************************************************/
typedef void (*pf_ed_calc_ctb)(
    ihevce_ed_ctxt_t *ps_ed_ctxt,
    ihevce_ed_blk_t *ps_ed_ctb,
    ihevce_ed_ctb_l1_t *ps_ed_ctb_l1,
    UWORD8 *pu1_src,
    WORD32 src_stride,
    WORD32 num_4x4_blks_x,
    WORD32 num_4x4_blks_y,
    WORD32 *nbr_flags,
    WORD32 i4_layer_id,
    WORD32 row_block_no,
    WORD32 col_block_no,
    ihevce_ipe_optimised_function_list_t *ps_ipe_optimised_function_list,
    ihevce_cmn_opt_func_t *ps_cmn_utils_optimised_function_list);

/*****************************************************************************/
/* Constant Macros                                                           */
/*****************************************************************************/
#define SATD_NOISE_FLOOR_THRESHOLD 16
#define MINIMUM_VARIANCE 15
#define SCALE_FACTOR_VARIANCE 20
#define SCALE_FACTOR_VARIANCE_8x8 60
#define MIN_SATD_THRSHLD 0
#define MAX_SATD_THRSHLD 64
#define SUB_NOISE_THRSHLD 0
#define MIN_BLKS 2

/*****************************************************************************/
/* Global variables                                                          */
/*****************************************************************************/

/**
*****************************************************************************
* @brief  list of pointers to luma intra pred functions
*****************************************************************************
*/
pf_intra_pred g_apf_lum_ip[NUM_IP_FUNCS];

/*****************************************************************************/
/* Function Definitions                                                      */
/*****************************************************************************/

/*!
******************************************************************************
* \if Function name : ihevce_intra_populate_mode_bits_cost \endif
*
* \brief: look-up table of cost of signalling an intra mode in the
*  bitstream
*
*****************************************************************************
*/
void ihevce_intra_populate_mode_bits_cost(
    WORD32 top_intra_mode,
    WORD32 left_intra_mode,
    WORD32 available_top,
    WORD32 available_left,
    WORD32 cu_pos_y,
    UWORD16 *mode_bits_cost,
    WORD32 lambda)
{
    WORD32 i;
    // 5.5 * lambda
    UWORD16 five_bits_cost = COMPUTE_RATE_COST_CLIP30(11, lambda, (LAMBDA_Q_SHIFT + 1));

    (void)top_intra_mode;
    (void)left_intra_mode;
    (void)available_top;
    (void)available_left;
    (void)cu_pos_y;
    for(i = 0; i < NUM_MODES; i++)
    {
        mode_bits_cost[i] = five_bits_cost;
    }
}

/*!
******************************************************************************
* \if Function name : ihevce_8x8_sad_computer \endif
*
* \brief: compute sad between 2 8x8 blocks
*
*****************************************************************************
*/
UWORD16
    ihevce_8x8_sad_computer(UWORD8 *pu1_src, UWORD8 *pu1_pred, WORD32 src_strd, WORD32 pred_strd)
{
    UWORD16 sad = 0;
    WORD32 i, j;

    for(i = 0; i < 8; i++)
    {
        for(j = 0; j < 8; j++)
        {
            sad += ABS(*pu1_src - *pu1_pred);
            pu1_src++;
            pu1_pred++;
        }
        pu1_src = pu1_src + (src_strd - 8);
        pu1_pred = pu1_pred + (pred_strd - 8);
    }

    return sad;
}

/*!
******************************************************************************
* \if Function name : ihevce_4x4_sad_computer \endif
*
* \brief: compute sad between 2 4x4 blocks
*
*****************************************************************************
*/
UWORD16
    ihevce_4x4_sad_computer(UWORD8 *pu1_src, UWORD8 *pu1_pred, WORD32 src_strd, WORD32 pred_strd)
{
    UWORD16 sad = 0;
    WORD32 i, j;

    for(i = 0; i < 4; i++)
    {
        for(j = 0; j < 4; j++)
        {
            sad += ABS(*pu1_src - *pu1_pred);
            pu1_src++;
            pu1_pred++;
        }
        pu1_src = pu1_src + (src_strd - 4);
        pu1_pred = pu1_pred + (pred_strd - 4);
    }

    return sad;
}

/*!
******************************************************************************
* \if Function name : ihevce_ed_4x4_find_best_modes \endif
*
* \brief: evaluate input 4x4 block for pre-selected list of angular and normal
*  intra modes and return best sad, cost
*
*****************************************************************************
*/
void ihevce_ed_4x4_find_best_modes(
    UWORD8 *pu1_src,
    WORD32 src_stride,
    UWORD8 *ref,
    UWORD16 *mode_bits_cost,
    UWORD8 *pu1_best_modes,
    WORD32 *pu1_best_sad_costs,
    WORD32 u1_low_resol,
    FT_SAD_COMPUTER *pf_4x4_sad_computer)
{
    WORD32 i;
    UWORD8 mode = 0, best_amode = 0, best_nmode = 0;
    UWORD8 pred[16];
    WORD32 sad = 0;
    WORD32 sad_cost = 0;
    WORD32 best_asad_cost = 0xFFFFF;
    WORD32 best_nsad_cost = 0xFFFFF;

    /* If lower layers, l1 or l2, all the 11 modes are evaluated */
    /* If L0 layer, all modes excluding DC and Planar are evaluated */
    if(1 == u1_low_resol)
        i = 0;
    else
        i = 2;

    /* Find the best non-angular and angular mode till level 4 */
    for(; i < 11; i++)
    {
        mode = gau1_modes_to_eval[i];
        g_apf_lum_ip[g_i4_ip_funcs[mode]](&ref[0], 0, &pred[0], 4, 4, mode);
        sad = pf_4x4_sad_computer(pu1_src, &pred[0], src_stride, 4);
        sad_cost = sad;
        sad_cost += mode_bits_cost[mode];
        if(mode < 2)
        {
            if(sad_cost < best_nsad_cost)
            {
                best_nmode = mode;
                best_nsad_cost = sad_cost;
            }
        }
        else
        {
            if(sad_cost < best_asad_cost)
            {
                best_amode = mode;
                best_asad_cost = sad_cost;
            }
        }
    }

    pu1_best_modes[0] = best_amode;
    pu1_best_sad_costs[0] = best_asad_cost;

    /* Accumalate the best non-angular mode and cost for the l1 and l2 layers */
    if(1 == u1_low_resol)
    {
        pu1_best_modes[1] = best_nmode;
        pu1_best_sad_costs[1] = best_nsad_cost;
    }
}

/*!
******************************************************************************
* \if Function name : ihevce_ed_calc_4x4_blk \endif
*
* \brief: evaluate input 4x4 block for all intra modes and return best sad &
*  cost
*
*****************************************************************************
*/
static void ihevce_ed_calc_4x4_blk(
    ihevce_ed_blk_t *ps_ed,
    UWORD8 *pu1_src,
    WORD32 src_stride,
    UWORD8 *ref,
    UWORD16 *mode_bits_cost,
    WORD32 *sad_ptr,
    WORD32 *pi4_best_satd,
    WORD32 i4_quality_preset,
    WORD32 *pi4_best_sad_cost,
    ihevce_ipe_optimised_function_list_t *ps_ipe_optimised_function_list)
{
    WORD32 i, i_end;
    UWORD8 mode, best_amode, best_nmode;
    UWORD8 pred[16];

    UWORD16 sad;
    WORD32 sad_cost = 0;
    WORD32 best_asad_cost = 0xFFFFF;
    WORD32 best_nsad_cost = 0xFFFFF;

    UWORD8 au1_best_modes[2];
    WORD32 ai4_best_sad_costs[2];

    /* L1/L2 resolution hence low resolution enable */
    WORD32 u1_low_resol = 1;

    UWORD8 modes_to_eval[2];

    /* The *pi4_best_satd will be consumed only if current
    layer has odd number of 4x4 blocks in either x or y
    direction. But the function hme_derive_num_layers() makes
    sure that every layer has width and height such that each one
    is a multiple of 16. Which makes pi4_best_satd useless. Hence
    feel free to remove pi4_best_satd. Concluded on 29th Aug13 */
    *pi4_best_satd = -1;
    ps_ipe_optimised_function_list->pf_ed_4x4_find_best_modes(
        pu1_src,
        src_stride,
        ref,
        mode_bits_cost,
        au1_best_modes,
        ai4_best_sad_costs,
        u1_low_resol,
        ps_ipe_optimised_function_list->pf_4x4_sad_computer);

    best_nmode = au1_best_modes[1];
    best_amode = au1_best_modes[0];
    best_nsad_cost = ai4_best_sad_costs[1];
    best_asad_cost = ai4_best_sad_costs[0];

    /* Updation of pi4_best_satd here needed iff the mode given by
    ihevce_ed_4x4_find_best_modes() comes out to be
    the best mode at the end of the function */
    *pi4_best_satd = best_asad_cost - mode_bits_cost[best_amode];

    /* Around best level 4 angular mode, search for best level 2 mode */
    modes_to_eval[0] = best_amode - 2;
    modes_to_eval[1] = best_amode + 2;
    i = 0;
    i_end = 2;
    if(best_amode == 2)
        i = 1;
    else if(best_amode == 34)
        i_end = 1;
    for(; i < i_end; i++)
    {
        mode = modes_to_eval[i];
        g_apf_lum_ip[g_i4_ip_funcs[mode]](&ref[0], 0, &pred[0], 4, 4, mode);
        sad = ps_ipe_optimised_function_list->pf_4x4_sad_computer(pu1_src, &pred[0], src_stride, 4);
        sad_cost = sad;
        sad_cost += mode_bits_cost[mode];
        if(sad_cost < best_asad_cost)
        {
            best_amode = mode;
            best_asad_cost = sad_cost;
            *pi4_best_satd = sad;
        }
        sad_ptr[mode] = sad;
    }

    /*To be done : Add a flag here instead of preset condn*/
    if((i4_quality_preset < IHEVCE_QUALITY_P4))
    {
        /* Around best level 2 angular mode, search for best level 1 mode */
        modes_to_eval[0] = best_amode - 1;
        modes_to_eval[1] = best_amode + 1;
        i = 0;
        i_end = 2;
        if(best_amode == 2)
            i = 1;
        else if(best_amode == 34)
            i_end = 1;
        for(; i < i_end; i++)
        {
            mode = modes_to_eval[i];
            g_apf_lum_ip[g_i4_ip_funcs[mode]](&ref[0], 0, &pred[0], 4, 4, mode);
            sad = ps_ipe_optimised_function_list->pf_4x4_sad_computer(
                pu1_src, &pred[0], src_stride, 4);
            sad_cost = sad;
            sad_cost += mode_bits_cost[mode];
            if(sad_cost < best_asad_cost)
            {
                best_amode = mode;
                best_asad_cost = sad_cost;
                *pi4_best_satd = sad;
            }
            sad_ptr[mode] = sad;
        }
    }

    if(best_asad_cost < best_nsad_cost)
    {
        ps_ed->best_mode = best_amode;
        *pi4_best_sad_cost = best_asad_cost;
    }
    else
    {
        ps_ed->best_mode = best_nmode;
        *pi4_best_sad_cost = best_nsad_cost;
    }
    ps_ed->intra_or_inter = 0;
    ps_ed->merge_success = 0;
}

/*!
******************************************************************************
* \if Function name : ihevce_ed_calc_8x8_blk \endif
*
* \brief: evaluate input 8x8 block for intra modes basing on the intra mode
*  decisions made at 4x4 level. This function also makes a decision whether
*  to split blk in to 4x4 partitions or not.
*
*****************************************************************************
*/
static void ihevce_ed_calc_8x8_blk(
    ihevce_ed_ctxt_t *ps_ed_ctxt,
    ihevce_ed_blk_t *ps_ed_8x8,
    UWORD8 *pu1_src,
    WORD32 src_stride,
    WORD32 *nbr_flags_ptr,
    WORD32 *top_intra_mode_ptr,
    WORD32 *left_intra_mode_ptr,
    WORD32 cu_pos_y,
    WORD32 lambda,
    WORD32 *sad_ptr_8x8,
    WORD32 *pi4_best_satd,
    WORD32 i4_layer_id,
    WORD32 i4_quality_preset,
    WORD32 i4_slice_type,
    WORD32 *pi4_best_sad_cost_8x8_l1_ipe,
    WORD32 *pi4_best_sad_8x8_l1_ipe,
    WORD32 *pi4_sum_4x4_satd,
    WORD32 *pi4_min_4x4_satd,
    ihevce_ipe_optimised_function_list_t *ps_ipe_optimised_function_list,
    ihevce_cmn_opt_func_t *ps_cmn_utils_optimised_function_list)
{
    WORD32 i, j;
    WORD32 nbr_flags, nbr_flags_TR;
    UWORD8 *pu1_src_4x4;
    WORD32 top_available;
    WORD32 left_available;
    ihevce_ed_blk_t *ps_ed_4x4 = ps_ed_8x8;
    WORD32 top_intra_mode;
    WORD32 left_intra_mode;
    WORD32 next_left_intra_mode;
    WORD32 *sad_ptr = sad_ptr_8x8;
    UWORD8 *pu1_src_arr[4];
    WORD32 i4_4x4_best_sad_cost[4];
    func_selector_t *ps_func_selector = ps_ed_ctxt->ps_func_selector;
    ihevc_intra_pred_luma_ref_substitution_ft *pf_intra_pred_luma_ref_substitution =
        ps_func_selector->ihevc_intra_pred_luma_ref_substitution_fptr;

    (void)i4_slice_type;

    /* Compute ref samples for 8x8 merge block */
    nbr_flags = nbr_flags_ptr[0];
    nbr_flags_TR = nbr_flags_ptr[1];

    if(CHECK_TR_AVAILABLE(nbr_flags_TR))
    {
        SET_TR_AVAILABLE(nbr_flags);
    }
    else
    {
        SET_TR_UNAVAILABLE(nbr_flags);
    }

    if(CHECK_BL_AVAILABLE(nbr_flags))
    {
        SET_BL_AVAILABLE(nbr_flags);
    }
    else
    {
        SET_BL_UNAVAILABLE(nbr_flags);
    }

    /* call the function which populates ref data for intra predicion */
    pf_intra_pred_luma_ref_substitution(
        pu1_src - src_stride - 1,
        pu1_src - src_stride,
        pu1_src - 1,
        src_stride,
        8,
        nbr_flags,
        &ps_ed_ctxt->au1_ref_8x8[0][0],
        0);

    for(i = 0; i < 2; i++)
    {
        pu1_src_4x4 = pu1_src + i * 4 * src_stride;
        cu_pos_y += i * 4;
        next_left_intra_mode = left_intra_mode_ptr[i];
        for(j = 0; j < 2; j++)
        {
            WORD32 i4_best_satd;
            pu1_src_arr[i * 2 + j] = pu1_src_4x4;
            nbr_flags = nbr_flags_ptr[i * 8 + j];
            top_intra_mode = top_intra_mode_ptr[j];
            left_intra_mode = next_left_intra_mode;
            /* call the function which populates ref data for intra predicion */
            pf_intra_pred_luma_ref_substitution(
                pu1_src_4x4 - src_stride - 1,
                pu1_src_4x4 - src_stride,
                pu1_src_4x4 - 1,
                src_stride,
                4,
                nbr_flags,
                &ps_ed_ctxt->au1_ref_full_ctb[i * 2 + j][0],
                0);

            top_available = CHECK_T_AVAILABLE(nbr_flags);
            left_available = CHECK_L_AVAILABLE(nbr_flags);
            /* call the function which populates sad cost for all the modes */
            ihevce_intra_populate_mode_bits_cost(
                top_intra_mode,
                left_intra_mode,
                top_available,
                left_available,
                cu_pos_y,
                &ps_ed_ctxt->au2_mode_bits_cost_full_ctb[i * 2 + j][0],
                lambda);
            ihevce_ed_calc_4x4_blk(
                ps_ed_4x4,
                pu1_src_4x4,
                src_stride,
                &ps_ed_ctxt->au1_ref_full_ctb[i * 2 + j][0],
                &ps_ed_ctxt->au2_mode_bits_cost_full_ctb[i * 2 + j][0],
                sad_ptr,
                &i4_best_satd,
                i4_quality_preset,
                &i4_4x4_best_sad_cost[i * 2 + j],
                ps_ipe_optimised_function_list);

            top_intra_mode_ptr[j] = ps_ed_4x4->best_mode;
            next_left_intra_mode = ps_ed_4x4->best_mode;
            pu1_src_4x4 += 4;
            ps_ed_4x4 += 1;
            sad_ptr += NUM_MODES;
        }
        left_intra_mode_ptr[i] = next_left_intra_mode;
    }

    /* 8x8 merge */
    {
        UWORD8 modes_to_eval[6];
        WORD32 sad;
        UWORD8 pred[16];
        UWORD8 pred_8x8[64] = { 0 };
        WORD32 merge_success;
        UWORD8 mode;

        ps_ed_4x4 = ps_ed_8x8;
        mode = (ps_ed_4x4)->best_mode;

        *pi4_best_satd = -1;

        merge_success =
            ((((ps_ed_4x4)->best_mode == (ps_ed_4x4 + 1)->best_mode) +
              ((ps_ed_4x4)->best_mode == (ps_ed_4x4 + 2)->best_mode) +
              ((ps_ed_4x4)->best_mode == (ps_ed_4x4 + 3)->best_mode)) == 3);

        {
            WORD32 i4_satd;
            //UWORD16 au2_4x4_sad_cost_array[4];/*SAD of 4x4 blocks*/
            UWORD16 u2_sum_best_4x4_sad_cost; /*Sum of 4x4 sad costs*/
            UWORD16 u2_sum_best_4x4_satd_cost; /*Sum of 4x4 satd costs*/
            UWORD8 u1_best_8x8_mode; /*8x8 mode.*/
            UWORD16 u2_best_8x8_cost; /*8x8 Cost. Can store SATD/SAD cost*/
            WORD32 i4_best_8x8_sad_satd; /* SATD/SAD value of 8x8 block*/
            UWORD16 au2_8x8_costs[6] = { 0 }; /*Cost of 8x8 block for 6 modes*/
            UWORD8 u1_cond_4x4_satd; /*condition if 4x4 SATD needs to be done*/
            UWORD8 u1_cond_8x8_satd; /*condition if 8x8 SATD needs to be done*/
            UWORD8 u1_good_quality;
            WORD32 i4_merge_success_stage2;

            /*Initiallization*/
            *pi4_best_satd = 0;
            u2_best_8x8_cost = (UWORD16)(-1) /*max value*/;
            u2_sum_best_4x4_sad_cost = 0;
            *pi4_sum_4x4_satd = -1;
            *pi4_min_4x4_satd = 0x7FFFFFFF;
            i4_best_8x8_sad_satd = 0;
            u2_sum_best_4x4_satd_cost = 0;
            u1_best_8x8_mode = ps_ed_4x4->best_mode;

            /*We thought of "replacing" SATDs by SADs for 4x4 vs 8x8 decision
            for speed improvement, but it gave opposite results. Setting
            good_quality to 1 in order to throw away the idea of "replacing".*/
            u1_good_quality = 1;
            //u1_good_quality = ((i4_quality_preset != IHEVCE_QUALITY_P5)
            //  && (i4_quality_preset != IHEVCE_QUALITY_P4));

            /*Needed to disable some processing based on speed preset*/
            i4_merge_success_stage2 = 0;

            /*Store SAD cost of 4x4 blocks */
            for(i = 0; i < 4; i++)
            {
                //au2_4x4_sad_cost_array[i] = (ps_ed_4x4 + i)->best_sad_cost;
                u2_sum_best_4x4_sad_cost +=
                    i4_4x4_best_sad_cost[i];  //(ps_ed_4x4 + i)->best_sad_cost;
                modes_to_eval[i] = (ps_ed_4x4 + i)->best_mode;
                /*NOTE_01: i4_4x4_satd is not used anywhere at present.
                Setting it to zero to avoid ASSERT failure */
                /*Now taken care of incomplete CTB*/
                //(ps_ed_4x4 + i)->i4_4x4_satd = 0;
            }

            /*Calculate SATD/SAd for 4x4 blocks*/
            /*For (layer_2 && high_speed): No need to get 4x4 SATDs bcoz
            it won't have any impact on quality but speed will improve.*/
            u1_cond_4x4_satd = ((1 == i4_layer_id) || (u1_good_quality && (!merge_success)));

            if(u1_cond_4x4_satd)
            {
                *pi4_sum_4x4_satd = 0;
                /*FYI: 1. Level 2 doesn't need the SATD.
                2. The 4x4 vs. 8x8 decision for high_speed will
                happen based on SAD. */
                /*Get SATD for 4x4 blocks */
                for(i = 0; i < 4; i++)
                {
                    mode = modes_to_eval[i];
                    g_apf_lum_ip[g_i4_ip_funcs[mode]](
                        &ps_ed_ctxt->au1_ref_full_ctb[i][0], 0, &pred[0], 4, 4, mode);

                    i4_satd = ps_cmn_utils_optimised_function_list->pf_HAD_4x4_8bit(
                        pu1_src_arr[i], src_stride, &pred[0], 4, NULL, 0);

                    {
                        /*Save 4x4x satd in ed blk struct */
                        (ps_ed_4x4 + i)->i4_4x4_satd = i4_satd;
                    }

                    /*(ps_ed_4x4 + i)->i4_4x4_satd = i4_satd; // See NOTE_01*/
                    u2_sum_best_4x4_satd_cost +=
                        ((UWORD16)i4_satd + ps_ed_ctxt->au2_mode_bits_cost_full_ctb[i][mode]);
                    *pi4_best_satd += i4_satd;
                }
            }
            /* Not being used in current code */
            else /* (Level_2 && extreme_speed) */
            {
                /******DONT ENTER HERE AT aNY COST***************************/
                /* Transistor killers lie ahead!!!!!!! */
                /*This else part is not getting executed as of now*/
                if(2 != i4_layer_id)
                    ASSERT(0);
                /*Update values by SAD_cost_array */
                for(i = 0; i < 4; i++)
                {
                    mode = modes_to_eval[i];
                    //u2_sum_best_4x4_satd_cost += au2_4x4_sad_cost_array[i];
                    //sad = (WORD32)((ps_ed_4x4 + i)->best_sad_cost - ps_ed_ctxt->au2_mode_bits_cost_full_ctb[i][mode]);
                    sad = (WORD32)(
                        i4_4x4_best_sad_cost[i] - ps_ed_ctxt->au2_mode_bits_cost_full_ctb[i][mode]);
                    *pi4_sum_4x4_satd += sad;
                    /*(ps_ed_4x4 + i)->i4_4x4_satd = sad;// See NOTE_01*/
                    *pi4_best_satd += sad;

                    if(*pi4_min_4x4_satd > sad)
                        *pi4_min_4x4_satd = sad;
                }
            }
            if(!merge_success) /*If the modes are not identical*/
            {
                UWORD8 i1_start; /* no of modes to evaluate */
                UWORD8 ai1_modes[6];

                /* Prepare 6 candidates for 8x8 block. Two are DC and planar */
                ai1_modes[4] = 0;
                ai1_modes[5] = 1;
                i1_start = 4;

                /*Assign along with removing duplicates rest 4 candidates. */
                for(i = 3; i >= 0; i--)
                {
                    WORD8 i1_fresh_mode_flag = 1;
                    mode = modes_to_eval[i];
                    /*Check if duplicate already exists in ai1_modes*/
                    for(j = i1_start; j < 6; j++)
                    {
                        if(mode == ai1_modes[j])
                            i1_fresh_mode_flag = 0;
                    }
                    if(i1_fresh_mode_flag)
                    {
                        i1_start--;
                        ai1_modes[i1_start] = mode;
                    }
                }

                /*Calculate SATD/SAD of 8x8 block for all modes*/
                /*If (u1_good_quality == 0) then SATD gets replaced by SAD*/
                if(u1_good_quality && (i4_quality_preset <= IHEVCE_QUALITY_P4))
                {
                    //7.5 * lambda to incorporate transfrom flags
                    u2_sum_best_4x4_satd_cost +=
                        (COMPUTE_RATE_COST_CLIP30(12, lambda, (LAMBDA_Q_SHIFT + 1)));

                    /*Loop over all modes for calculating SATD*/
                    for(i = i1_start; i < 6; i++)
                    {
                        mode = ai1_modes[i];
                        g_apf_lum_ip[g_i4_ip_funcs[mode]](
                            &ps_ed_ctxt->au1_ref_8x8[0][0], 0, &pred_8x8[0], 8, 8, mode);

                        i4_satd = ps_cmn_utils_optimised_function_list->pf_HAD_8x8_8bit(
                            pu1_src_arr[0], src_stride, &pred_8x8[0], 8, NULL, 0);

                        au2_8x8_costs[i] =
                            ((UWORD16)i4_satd + ps_ed_ctxt->au2_mode_bits_cost_full_ctb[0][mode]);

                        /*Update data correspoinding to least 8x8 cost */
                        if(au2_8x8_costs[i] <= u2_best_8x8_cost)
                        {
                            u2_best_8x8_cost = au2_8x8_costs[i];
                            i4_best_8x8_sad_satd = i4_satd;
                            u1_best_8x8_mode = mode;
                        }
                    }
                    /*8x8 vs 4x4 decision based on SATD values*/
                    if((u2_best_8x8_cost <= u2_sum_best_4x4_satd_cost) || (u2_best_8x8_cost <= 300))
                    {
                        i4_merge_success_stage2 = 1;
                    }

                    /* EIID: Early inter-intra decision */
                    /* Find the SAD based cost for 8x8 block for best mode */
                    if(/*(ISLICE != i4_slice_type) && */ (1 == i4_layer_id))
                    {
                        UWORD8 i4_best_8x8_mode = u1_best_8x8_mode;
                        WORD32 i4_best_8x8_sad_curr;

                        g_apf_lum_ip[g_i4_ip_funcs[i4_best_8x8_mode]](
                            &ps_ed_ctxt->au1_ref_8x8[0][0], 0, &pred_8x8[0], 8, 8, i4_best_8x8_mode);

                        i4_best_8x8_sad_curr = ps_ipe_optimised_function_list->pf_8x8_sad_computer(
                            pu1_src_arr[0], &pred_8x8[0], src_stride, 8);

                        //register best sad in the context
                        //ps_ed_8x8->i4_best_sad_8x8_l1_ipe = i4_best_8x8_sad_curr;

                        //register the best cost in the context
                        //[0]th index is used since all 4 blocks are having same cost right now
                        //also it doesnt depends on mode. It only depends on the lambda

                        *pi4_best_sad_cost_8x8_l1_ipe =
                            i4_best_8x8_sad_curr +
                            ps_ed_ctxt->au2_mode_bits_cost_full_ctb[0][i4_best_8x8_mode];
                        *pi4_best_sad_8x8_l1_ipe = i4_best_8x8_sad_curr;
                    }
                }
                else /*If high_speed or extreme speed*/
                {
                    //7.5 * lambda to incorporate transfrom flags
                    u2_sum_best_4x4_sad_cost +=
                        (COMPUTE_RATE_COST_CLIP30(12, lambda, (LAMBDA_Q_SHIFT + 1)));

                    /*Loop over all modes for calculating SAD*/
                    for(i = i1_start; i < 6; i++)
                    {
                        mode = ai1_modes[i];
                        g_apf_lum_ip[g_i4_ip_funcs[mode]](
                            &ps_ed_ctxt->au1_ref_8x8[0][0], 0, &pred_8x8[0], 8, 8, mode);

                        sad = ps_ipe_optimised_function_list->pf_8x8_sad_computer(
                            pu1_src_arr[0], &pred_8x8[0], src_stride, 8);

                        au2_8x8_costs[i] +=
                            ((UWORD16)sad + ps_ed_ctxt->au2_mode_bits_cost_full_ctb[0][mode]);

                        /*Find the data correspoinding to least cost */
                        if(au2_8x8_costs[i] <= u2_best_8x8_cost)
                        {
                            u2_best_8x8_cost = au2_8x8_costs[i];
                            i4_best_8x8_sad_satd = sad;
                            u1_best_8x8_mode = mode;
                        }
                    }
                    /*8x8 vs 4x4 decision based on SAD values*/
                    if((u2_best_8x8_cost <= u2_sum_best_4x4_sad_cost) || (u2_best_8x8_cost <= 300))
                    {
                        i4_merge_success_stage2 = 1;
                    }

                    /* EIID: Early inter-intra decision */
                    /* Find the SAD based cost for 8x8 block for best mode */
                    if(/*(ISLICE != i4_slice_type) && */ (1 == i4_layer_id))
                    {
                        //UWORD8 i4_best_8x8_mode = u1_best_8x8_mode;
                        WORD32 i4_best_8x8_sad_cost_curr = u2_best_8x8_cost;

                        //register best sad in the context
                        //ps_ed_8x8->i4_best_sad_8x8_l1_ipe = i4_best_8x8_sad_curr;

                        //register the best cost in the context
                        *pi4_best_sad_cost_8x8_l1_ipe = i4_best_8x8_sad_cost_curr;
                        *pi4_best_sad_8x8_l1_ipe =
                            i4_best_8x8_sad_satd;  //i4_best_8x8_sad_cost_curr;
                    }
                }
            }

            /***** Modes for 4x4 and 8x8 are decided before this point ****/
            if(merge_success || i4_merge_success_stage2)
            {
                /*FYI: 1. 8x8 SATD is not needed if merge is failed.
                2. For layer_2: SATD won't be calculated for 8x8. So
                the best_8x8_cost is SAD-cost. */

                /* Store the 8x8 level data in the first 4x4 block*/
                ps_ed_4x4->merge_success = 1;
                ps_ed_4x4->best_merge_mode = u1_best_8x8_mode;
                /* ps_ed_4x4->best_merge_sad_cost = u2_best_8x8_cost;
                This data is not getting consumed anywhere at present */

                top_intra_mode_ptr[0] = u1_best_8x8_mode;
                top_intra_mode_ptr[1] = u1_best_8x8_mode;
                left_intra_mode_ptr[0] = u1_best_8x8_mode;
                left_intra_mode_ptr[1] = u1_best_8x8_mode;

                /*If it is layer_1 and high_speed*/
                u1_cond_8x8_satd =
                    ((1 == i4_layer_id) &&
                     (merge_success || ((!u1_good_quality) && i4_merge_success_stage2)));
                if(u1_cond_8x8_satd)
                {
                    mode = u1_best_8x8_mode;
                    g_apf_lum_ip[g_i4_ip_funcs[mode]](
                        &ps_ed_ctxt->au1_ref_8x8[0][0], 0, &pred_8x8[0], 8, 8, mode);

                    if(i4_quality_preset > IHEVCE_QUALITY_P3)
                    {
                        i4_satd = ps_ipe_optimised_function_list->pf_8x8_sad_computer(
                            pu1_src_arr[0], &pred_8x8[0], src_stride, 8);
                    }
                    else
                    {
                        i4_satd = ps_cmn_utils_optimised_function_list->pf_HAD_8x8_8bit(
                            pu1_src_arr[0], src_stride, &pred_8x8[0], 8, NULL, 0);
                    }
                    /* u2_best_8x8_cost = ((UWORD16)i4_satd + mode_bits_cost[0][mode]);
                    This data is not getting consumed at present */
                    i4_best_8x8_sad_satd = i4_satd;
                }
                *pi4_best_satd = i4_best_8x8_sad_satd;

                /* EIID: Early inter-intra decision */
                /* Find the SAD based cost for 8x8 block for best mode */
                if(/*(ISLICE != i4_slice_type) && */ (1 == i4_layer_id))
                {
                    UWORD8 i4_best_8x8_mode = u1_best_8x8_mode;
                    WORD32 i4_best_8x8_sad_curr;

                    g_apf_lum_ip[g_i4_ip_funcs[i4_best_8x8_mode]](
                        &ps_ed_ctxt->au1_ref_8x8[0][0], 0, &pred_8x8[0], 8, 8, i4_best_8x8_mode);

                    i4_best_8x8_sad_curr = ps_ipe_optimised_function_list->pf_8x8_sad_computer(
                        pu1_src_arr[0], &pred_8x8[0], src_stride, 8);
                    //register best sad in the context
                    //ps_ed_8x8->i4_best_sad_8x8_l1_ipe = i4_best_8x8_sad_curr;

                    //register the best cost in the context
                    //[0]th index is used since all 4 blocks are having same cost right now
                    //also it doesnt depends on mode. It only depends on the lambda

                    *pi4_best_sad_cost_8x8_l1_ipe =
                        i4_best_8x8_sad_curr +
                        ps_ed_ctxt->au2_mode_bits_cost_full_ctb[0][i4_best_8x8_mode];
                    *pi4_best_sad_8x8_l1_ipe = i4_best_8x8_sad_curr;

                }  // EIID ends

            }  //if(merge_success || i4_merge_success_stage2)
        }
    }
}

/*!
******************************************************************************
* \if Function name : ihevce_ed_calc_incomplete_ctb \endif
*
* \brief: performs L1 8x8 and 4x4 intra mode analysis
*
*****************************************************************************
*/
void ihevce_ed_calc_incomplete_ctb(
    ihevce_ed_ctxt_t *ps_ed_ctxt,
    ihevce_ed_blk_t *ps_ed_ctb,
    ihevce_ed_ctb_l1_t *ps_ed_ctb_l1,
    UWORD8 *pu1_src,
    WORD32 src_stride,
    WORD32 num_4x4_blks_x,
    WORD32 num_4x4_blks_y,
    WORD32 *nbr_flags,
    WORD32 i4_layer_id,
    WORD32 i4_row_block_no,
    WORD32 i4_col_block_no,
    ihevce_ipe_optimised_function_list_t *ps_ipe_optimised_function_list,
    ihevce_cmn_opt_func_t *ps_cmn_utils_optimised_function_list)
{
    WORD32 i, j, k;
    WORD32 z_scan_idx = 0;
    WORD32 z_scan_act_idx = 0;
    ihevc_intra_pred_luma_ref_substitution_ft *pf_intra_pred_luma_ref_substitution =
        ps_ed_ctxt->ps_func_selector->ihevc_intra_pred_luma_ref_substitution_fptr;

    //UWORD8 ref[18];
    //WORD32 top_intra_modes[20];
    WORD32 *sad_ptr = &ps_ed_ctxt->sad[0];
    WORD32 lambda = ps_ed_ctxt->lambda;
    //UWORD16 mode_bits_cost[NUM_MODES];

    UWORD8 *pu1_src_8x8;
    ihevce_ed_blk_t *ps_ed_8x8, *ps_ed_4x4;
    WORD32 *top_intra_mode_ptr;
    WORD32 *left_intra_mode_ptr = ps_ed_ctxt->left_ctb_intra_modes;
    WORD32 *nbr_flags_ptr;
    WORD32 top_intra_mode;
    WORD32 left_intra_mode;
    WORD32 next_left_intra_mode;
    WORD32 nbr_flag = 0;
    WORD32 top_available;
    WORD32 left_available;
    UWORD8 *pu1_src_4x4;
    WORD32 left_over_4x4_blks;
    WORD32 i4_incomplete_sum_4x4_satd = 0;
    WORD32 i4_incomplete_min_4x4_satd = 0x7FFFFFFF;
    WORD32 i4_best_sad_cost_8x8_l1_ipe, i4_best_sad_8x8_l1_ipe, i4_sum_4x4_satd, i4_min_4x4_satd;

    (void)i4_row_block_no;
    (void)i4_col_block_no;
    /*Find the modulated qp of 16*16 at L2 from 8*8 SATDs in L2
    THis is used as 64*64 Qp in L0*/
    /*For Incomplete CTB, init all SATD to -1 and then popualate for the complete 8x8 blocks (CU 16 in L0)*/
    /* Not populated for 4x4 blocks (CU 8 in L0), can be done */
    /*Also, not 32x32 satd is not populated, as it would correspong to CU 64 and it is not an incomplete CTB */
    if(i4_layer_id == 1)
    {
        WORD32 i4_i;

        for(i4_i = 0; i4_i < 64; i4_i++)
        {
            (ps_ed_ctb + i4_i)->i4_4x4_satd = -1;
            (ps_ed_ctb + i4_i)->i4_4x4_cur_satd = -1;
        }

        for(i4_i = 0; i4_i < 16; i4_i++)
        {
            ps_ed_ctb_l1->i4_sum_4x4_satd[i4_i] = -2;
            ps_ed_ctb_l1->i4_min_4x4_satd[i4_i] = 0x7FFFFFFF;
            ps_ed_ctb_l1->i4_8x8_satd[i4_i][0] = -2;
            ps_ed_ctb_l1->i4_8x8_satd[i4_i][1] = -2;
        }

        for(i4_i = 0; i4_i < 4; i4_i++)
        {
            ps_ed_ctb_l1->i4_16x16_satd[i4_i][0] = -2;
            ps_ed_ctb_l1->i4_16x16_satd[i4_i][1] = -2;
            ps_ed_ctb_l1->i4_16x16_satd[i4_i][2] = -2;
        }
        ps_ed_ctb_l1->i4_32x32_satd[0][0] = -2;
        ps_ed_ctb_l1->i4_32x32_satd[0][1] = -2;
        ps_ed_ctb_l1->i4_32x32_satd[0][2] = -2;

        ps_ed_ctb_l1->i4_32x32_satd[0][3] = -2;

        for(i4_i = 0; i4_i < 16; i4_i++)
        {
            ps_ed_ctb_l1->i4_best_satd_8x8[i4_i] = -1;
            ps_ed_ctb_l1->i4_best_sad_cost_8x8_l1_ipe[i4_i] = -1;
            ps_ed_ctb_l1->i4_best_sad_8x8_l1_ipe[i4_i] = -1;
            ps_ed_ctb_l1->i4_best_sad_cost_8x8_l1_me[i4_i] = -1;
            ps_ed_ctb_l1->i4_sad_cost_me_for_ref[i4_i] = -1;
            ps_ed_ctb_l1->i4_sad_me_for_ref[i4_i] = -1;
            ps_ed_ctb_l1->i4_best_sad_8x8_l1_me[i4_i] = -1;

            ps_ed_ctb_l1->i4_best_sad_8x8_l1_me_for_decide[i4_i] = -1;
        }
    }
    /*
    * src scan happens in raster scan order. ps_ed update happens in z-scan order.
    */
    for(i = 0; i < num_4x4_blks_x; i++)
    {
        ps_ed_ctxt->ai4_top_intra_modes_ic_ctb[i] = INTRA_DC;
    }
    next_left_intra_mode = left_intra_mode_ptr[0];
    for(i = 0; i < num_4x4_blks_y / 2; i++)
    {
        pu1_src_8x8 = pu1_src + i * 2 * 4 * src_stride;
        top_intra_mode_ptr = &ps_ed_ctxt->ai4_top_intra_modes_ic_ctb[0];
        nbr_flags_ptr = &nbr_flags[0] + 2 * 8 * i;

        for(j = 0; j < num_4x4_blks_x / 2; j++)
        {
            WORD32 i4_best_satd;
            // Multiply i by 16 since the
            // matrix is prepared for ctb_size = 64
            z_scan_idx = gau1_ctb_raster_to_zscan[i * 2 * 16 + j * 2];
            z_scan_act_idx = gau1_ctb_raster_to_zscan[i * 16 + j];
            ASSERT(z_scan_act_idx <= 15);
            ps_ed_8x8 = ps_ed_ctb + z_scan_idx;

            ihevce_ed_calc_8x8_blk(
                ps_ed_ctxt,
                ps_ed_8x8,
                pu1_src_8x8,
                src_stride,
                nbr_flags_ptr,
                top_intra_mode_ptr,
                left_intra_mode_ptr,
                i * 8,
                lambda,
                sad_ptr + z_scan_idx * NUM_MODES,
                &i4_best_satd,
                i4_layer_id,
                ps_ed_ctxt->i4_quality_preset,
                ps_ed_ctxt->i4_slice_type,
                &i4_best_sad_cost_8x8_l1_ipe,
                &i4_best_sad_8x8_l1_ipe,
                &i4_sum_4x4_satd,
                &i4_min_4x4_satd,
                ps_ipe_optimised_function_list,
                ps_cmn_utils_optimised_function_list);

            ASSERT(i4_best_satd >= 0);
            if(i4_layer_id == 1)
            {
                ps_ed_ctb_l1->i4_best_sad_cost_8x8_l1_ipe[z_scan_act_idx] =
                    i4_best_sad_cost_8x8_l1_ipe;
                ps_ed_ctb_l1->i4_best_sad_8x8_l1_ipe[z_scan_act_idx] = i4_best_sad_8x8_l1_ipe;
                ps_ed_ctb_l1->i4_best_satd_8x8[z_scan_act_idx] = i4_best_satd;
                ps_ed_ctxt->i8_sum_best_satd += i4_best_satd;
                ps_ed_ctxt->i8_sum_sq_best_satd += (i4_best_satd * i4_best_satd);
                //ps_ed_ctb_l1->i4_sum_4x4_satd[z_scan_act_idx] = i4_sum_4x4_satd;
                //ps_ed_ctb_l1->i4_min_4x4_satd[z_scan_act_idx] = i4_min_4x4_satd;
            }

            pu1_src_8x8 += 8;
            //ps_ed_8x8  += 4;
            top_intra_mode_ptr += 2;
            nbr_flags_ptr += 2;
        }

        next_left_intra_mode = left_intra_mode_ptr[0];
        left_over_4x4_blks = (num_4x4_blks_x - (2 * (num_4x4_blks_x / 2)));
        left_over_4x4_blks = left_over_4x4_blks * 2;

        pu1_src_4x4 = pu1_src_8x8;

        i4_incomplete_sum_4x4_satd = 0;
        i4_incomplete_min_4x4_satd = 0x7FFFFFFF;

        /* For leftover right 4x4 blks (num_4x4_blks_x - 2 *(num_4x4_blks_x/2))*/
        for(k = 0; k < left_over_4x4_blks; k++)
        {
            WORD32 i4_best_satd;
            WORD32 i4_dummy_sad_cost;
            // Multiply i by 16 since the
            // matrix is prepared for ctb_size = 64
            ASSERT(left_over_4x4_blks == 2);
            z_scan_idx = gau1_ctb_raster_to_zscan[i * 2 * 16 + k * 16 + j * 2];
            ps_ed_4x4 = ps_ed_ctb + z_scan_idx;

            top_intra_mode = ps_ed_ctxt->ai4_top_intra_modes_ic_ctb[j];
            left_intra_mode = next_left_intra_mode;

            nbr_flag = nbr_flags[i * 2 * 8 + k * 8 + j * 2];

            /* call the function which populates ref data for intra predicion */
            pf_intra_pred_luma_ref_substitution(
                pu1_src_4x4 - src_stride - 1,
                pu1_src_4x4 - src_stride,
                pu1_src_4x4 - 1,
                src_stride,
                4,
                nbr_flag,
                &ps_ed_ctxt->au1_ref_ic_ctb[0],
                0);

            top_available = CHECK_T_AVAILABLE(nbr_flag);
            left_available = CHECK_L_AVAILABLE(nbr_flag);
            /* call the function which populates sad cost for all the modes */
            ihevce_intra_populate_mode_bits_cost(
                top_intra_mode,
                left_intra_mode,
                top_available,
                left_available,
                i * 4,
                &ps_ed_ctxt->au2_mode_bits_cost_ic_ctb[0],
                lambda);

            ihevce_ed_calc_4x4_blk(
                ps_ed_4x4,
                pu1_src_4x4,
                src_stride,
                &ps_ed_ctxt->au1_ref_ic_ctb[0],
                &ps_ed_ctxt->au2_mode_bits_cost_ic_ctb[0],
                sad_ptr + z_scan_idx * NUM_MODES,
                &i4_best_satd,
                ps_ed_ctxt->i4_quality_preset,
                &i4_dummy_sad_cost,
                ps_ipe_optimised_function_list);

            ASSERT(i4_best_satd >= 0);
            if(i4_layer_id == 1)  //Can we ignore this check?
            {
                z_scan_act_idx = gau1_ctb_raster_to_zscan[i * 16 + j];
                /*Note : The satd population is not populated for last 4*4 block in incomplete CTB */
                /* Which corresponds to CU 8 in L0 */

                /*MAM_VAR_L1 */
                i4_incomplete_sum_4x4_satd = i4_incomplete_sum_4x4_satd + i4_best_satd;
                if(i4_incomplete_min_4x4_satd >= i4_best_satd)
                    i4_incomplete_min_4x4_satd = i4_best_satd;
                ps_ed_ctxt->i8_sum_best_satd += i4_best_satd;
                ps_ed_ctxt->i8_sum_sq_best_satd += (i4_best_satd * i4_best_satd);
                if((k & 1) == 0)
                {
                    ps_ed_ctb_l1->i4_best_satd_8x8[z_scan_act_idx] = 0;
                }
                ps_ed_ctb_l1->i4_best_satd_8x8[z_scan_act_idx] += i4_best_satd;
            }

            ps_ed_ctxt->ai4_top_intra_modes_ic_ctb[j * 2] = ps_ed_4x4->best_mode;
            next_left_intra_mode = ps_ed_4x4->best_mode;
            pu1_src_4x4 += src_stride;
            left_intra_mode_ptr[k] = next_left_intra_mode;
        }
        left_intra_mode_ptr += 2;
    }

    if(num_4x4_blks_y & 1)
    {
        /* For leftover bottom 4x4 blks. (num_4x4_blks_x) */
        pu1_src_4x4 = pu1_src + i * 2 * 4 * src_stride;
        //memset(&ps_ed_ctb_l1->i4_best_satd_8x8[i][0],0,4*sizeof(WORD32));
        for(j = 0; j < num_4x4_blks_x; j++)
        {
            WORD32 i4_best_satd;
            WORD32 i4_dummy_sad_cost;
            // Multiply i by 16 since the
            // matrix is prepared for ctb_size = 64
            z_scan_idx = gau1_ctb_raster_to_zscan[i * 2 * 16 + j];
            ps_ed_4x4 = ps_ed_ctb + z_scan_idx;

            if((j & 1) == 0)
            {
                i4_incomplete_sum_4x4_satd = 0;
                i4_incomplete_min_4x4_satd = 0x7FFFFFFF;
            }

            top_intra_mode = ps_ed_ctxt->ai4_top_intra_modes_ic_ctb[j];
            left_intra_mode = next_left_intra_mode;

            nbr_flag = nbr_flags[i * 2 * 8 + j];

            /* call the function which populates ref data for intra predicion */
            pf_intra_pred_luma_ref_substitution(
                pu1_src_4x4 - src_stride - 1,
                pu1_src_4x4 - src_stride,
                pu1_src_4x4 - 1,
                src_stride,
                4,
                nbr_flag,
                &ps_ed_ctxt->au1_ref_ic_ctb[0],
                0);

            top_available = CHECK_T_AVAILABLE(nbr_flag);
            left_available = CHECK_L_AVAILABLE(nbr_flag);
            /* call the function which populates sad cost for all the modes */
            ihevce_intra_populate_mode_bits_cost(
                top_intra_mode,
                left_intra_mode,
                top_available,
                left_available,
                i * 4,
                &ps_ed_ctxt->au2_mode_bits_cost_ic_ctb[0],
                lambda);

            ihevce_ed_calc_4x4_blk(
                ps_ed_4x4,
                pu1_src_4x4,
                src_stride,
                &ps_ed_ctxt->au1_ref_ic_ctb[0],
                &ps_ed_ctxt->au2_mode_bits_cost_ic_ctb[0],
                sad_ptr + z_scan_idx * NUM_MODES,
                &i4_best_satd,
                ps_ed_ctxt->i4_quality_preset,
                &i4_dummy_sad_cost,
                ps_ipe_optimised_function_list);

            /*Note : The satd population is not populated for last 4*4 block in incomplete CTB */
            /* Which corresponds to CU 8 in L0 */

            /*MAM_VAR_L1 */
            ASSERT(i4_best_satd >= 0);
            if(i4_layer_id == 1)  //Can we ignore this check?
            {
                z_scan_act_idx = gau1_ctb_raster_to_zscan[i * 16 + (j >> 1)];
                if((j & 1) == 0)
                {
                    ps_ed_ctb_l1->i4_best_satd_8x8[z_scan_act_idx] = 0;
                }
                ps_ed_ctb_l1->i4_best_satd_8x8[z_scan_act_idx] += i4_best_satd;
                ps_ed_ctxt->i8_sum_best_satd += i4_best_satd;
                ps_ed_ctxt->i8_sum_sq_best_satd += (i4_best_satd * i4_best_satd);
                i4_incomplete_sum_4x4_satd = i4_incomplete_sum_4x4_satd + i4_best_satd;
                if(i4_incomplete_min_4x4_satd >= i4_best_satd)
                    i4_incomplete_min_4x4_satd = i4_best_satd;
            }

            ps_ed_ctxt->ai4_top_intra_modes_ic_ctb[j] = ps_ed_4x4->best_mode;
            next_left_intra_mode = ps_ed_4x4->best_mode;
            pu1_src_4x4 += 4;
        }
    }
    left_intra_mode_ptr[0] = next_left_intra_mode;
}

/*!
******************************************************************************
* \if Function name : ihevce_cu_level_qp_mod \endif
*
* \brief: Performs CU level QP modulation
*
*****************************************************************************
*/
WORD32 ihevce_cu_level_qp_mod(
    WORD32 i4_qscale,
    WORD32 i4_satd,
    long double ld_curr_frame_log_avg_act,
    float f_mod_strength,
    WORD32 *pi4_act_factor,
    WORD32 *pi4_q_scale_mod,
    rc_quant_t *ps_rc_quant_ctxt)
{
    WORD32 i4_temp_qscale;
    WORD32 i4_temp_qp;

    if(i4_satd != -1)
    {
        WORD32 i4_loc_satd = i4_satd;
        if(i4_loc_satd < 1)
        {
            i4_loc_satd = 1;
        }
        if((WORD32)ld_curr_frame_log_avg_act == 0)
        {
            *pi4_act_factor = (1 << (QP_LEVEL_MOD_ACT_FACTOR));
        }
        else
        {
            UWORD32 u4_log2_sq_cur_satd;
            ULWORD64 u8_sq_cur_satd;
            WORD32 qp_offset;

            ASSERT(USE_SQRT_AVG_OF_SATD_SQR);
            u8_sq_cur_satd = (i4_loc_satd * i4_loc_satd);
            GET_POS_MSB_64(u4_log2_sq_cur_satd, u8_sq_cur_satd);
            if(ABS((
                   long double)(((1 << u4_log2_sq_cur_satd) * POW_2_TO_1_BY_4) - ((long double)u8_sq_cur_satd))) >
               ABS((
                   long double)(((1 << u4_log2_sq_cur_satd) * POW_2_TO_3_BY_4) - ((long double)u8_sq_cur_satd))))
            {
                u4_log2_sq_cur_satd += 1;
            }
            qp_offset = (WORD32)(
                f_mod_strength *
                (float)((long double)u4_log2_sq_cur_satd - ld_curr_frame_log_avg_act));
            qp_offset = CLIP3(qp_offset, MIN_QP_MOD_OFFSET, MAX_QP_MOD_OFFSET);
            *pi4_act_factor = (WORD32)(
                gad_look_up_activity[qp_offset + ABS(MIN_QP_MOD_OFFSET)] *
                (1 << QP_LEVEL_MOD_ACT_FACTOR));
        }

        ASSERT(*pi4_act_factor > 0);
        i4_temp_qscale = ((i4_qscale * (*pi4_act_factor)) + (1 << (QP_LEVEL_MOD_ACT_FACTOR - 1))) >>
                         QP_LEVEL_MOD_ACT_FACTOR;
    }
    else
    {
        i4_temp_qscale = i4_qscale;
        *pi4_act_factor = (1 << QP_LEVEL_MOD_ACT_FACTOR);
    }
    ASSERT(*pi4_act_factor > 0);

    if(i4_temp_qscale > ps_rc_quant_ctxt->i2_max_qscale)
    {
        i4_temp_qscale = ps_rc_quant_ctxt->i2_max_qscale;
    }
    else if(i4_temp_qscale < ps_rc_quant_ctxt->i2_min_qscale)
    {
        i4_temp_qscale = ps_rc_quant_ctxt->i2_min_qscale;
    }
    /*store q scale for stat gen for I frame model*/
    /*Here activity factor is not modified as the cu qp would be clipped in rd-opt stage*/
    *pi4_q_scale_mod = i4_temp_qscale;
    i4_temp_qp = ps_rc_quant_ctxt->pi4_qscale_to_qp[i4_temp_qscale];
    if(i4_temp_qp > ps_rc_quant_ctxt->i2_max_qp)
    {
        i4_temp_qp = ps_rc_quant_ctxt->i2_max_qp;
    }
    else if(i4_temp_qp < ps_rc_quant_ctxt->i2_min_qp)
    {
        i4_temp_qp = ps_rc_quant_ctxt->i2_min_qp;
    }
    return (i4_temp_qp);
}

/*!
******************************************************************************
* \if Function name : ihevce_ed_calc_ctb \endif
*
* \brief: performs L1 8x8 and 4x4 intra mode analysis
*
*****************************************************************************
*/
void ihevce_ed_calc_ctb(
    ihevce_ed_ctxt_t *ps_ed_ctxt,
    ihevce_ed_blk_t *ps_ed_ctb,
    ihevce_ed_ctb_l1_t *ps_ed_ctb_l1,
    UWORD8 *pu1_src,
    WORD32 src_stride,
    WORD32 num_4x4_blks_x,
    WORD32 num_4x4_blks_y,
    WORD32 *nbr_flags,
    WORD32 i4_layer_id,
    WORD32 i4_row_block_no,
    WORD32 i4_col_block_no,
    ihevce_ipe_optimised_function_list_t *ps_ipe_optimised_function_list,
    ihevce_cmn_opt_func_t *ps_cmn_utils_optimised_function_list)
{
    WORD32 i, j;
    WORD32 z_scan_idx = 0;
    WORD32 z_scan_act_idx = 0;
    ihevce_ed_blk_t *ps_ed_8x8;
    UWORD8 *pu1_src_8x8;

    WORD32 top_intra_modes[20];
    WORD32 *top_intra_mode_ptr;
    WORD32 *left_intra_mode_ptr = ps_ed_ctxt->left_ctb_intra_modes;

    WORD32 *sad_ptr = &ps_ed_ctxt->sad[0];
    WORD32 lambda = ps_ed_ctxt->lambda;
    WORD32 *nbr_flags_ptr;
    WORD32 i4_best_sad_cost_8x8_l1_ipe, i4_best_sad_8x8_l1_ipe, i4_sum_4x4_satd, i4_min_4x4_satd;

    (void)num_4x4_blks_y;
    (void)i4_row_block_no;
    (void)i4_col_block_no;
    ASSERT(num_4x4_blks_x % 2 == 0);
    ASSERT(num_4x4_blks_y % 2 == 0);
    ASSERT((num_4x4_blks_x == 4) || (num_4x4_blks_x == 8));
    ASSERT((num_4x4_blks_y == 4) || (num_4x4_blks_y == 8));

    if(i4_layer_id == 1)
    {
        WORD32 i4_i;

        for(i4_i = 0; i4_i < 64; i4_i++)
        {
            (ps_ed_ctb + i4_i)->i4_4x4_satd = -1;
            (ps_ed_ctb + i4_i)->i4_4x4_cur_satd = -1;
        }

        for(i4_i = 0; i4_i < 16; i4_i++)
        {
            ps_ed_ctb_l1->i4_sum_4x4_satd[i4_i] = -2;
            ps_ed_ctb_l1->i4_min_4x4_satd[i4_i] = 0x7FFFFFFF;
            ps_ed_ctb_l1->i4_8x8_satd[i4_i][0] = -2;
            ps_ed_ctb_l1->i4_8x8_satd[i4_i][1] = -2;
        }

        for(i4_i = 0; i4_i < 4; i4_i++)
        {
            ps_ed_ctb_l1->i4_16x16_satd[i4_i][0] = -2;
            ps_ed_ctb_l1->i4_16x16_satd[i4_i][1] = -2;
            ps_ed_ctb_l1->i4_16x16_satd[i4_i][2] = -2;
        }
        ps_ed_ctb_l1->i4_32x32_satd[0][0] = -2;
        ps_ed_ctb_l1->i4_32x32_satd[0][1] = -2;
        ps_ed_ctb_l1->i4_32x32_satd[0][2] = -2;
        ps_ed_ctb_l1->i4_32x32_satd[0][3] = -2;
        for(i4_i = 0; i4_i < 16; i4_i++)
        {
            ps_ed_ctb_l1->i4_best_sad_cost_8x8_l1_me[i4_i] = -2;
            ps_ed_ctb_l1->i4_sad_cost_me_for_ref[i4_i] = -2;
            ps_ed_ctb_l1->i4_sad_me_for_ref[i4_i] = -2;
            ps_ed_ctb_l1->i4_best_sad_8x8_l1_me[i4_i] = -2;

            ps_ed_ctb_l1->i4_best_sad_8x8_l1_me_for_decide[i4_i] = -2;

            ps_ed_ctb_l1->i4_best_satd_8x8[i4_i] = -2;
            ps_ed_ctb_l1->i4_best_sad_cost_8x8_l1_ipe[i4_i] = -2;
            ps_ed_ctb_l1->i4_best_sad_8x8_l1_ipe[i4_i] = -2;
        }
    }
    /*
    * src scan happens in raster scan order. ps_ed update happens in z-scan order.
    */
    for(i = 0; i < num_4x4_blks_x; i++)
    {
        top_intra_modes[i] = INTRA_DC;
    }
    for(i = 0; i < num_4x4_blks_x / 2; i++)
    {
        pu1_src_8x8 = pu1_src + i * 2 * 4 * src_stride;
        top_intra_mode_ptr = &top_intra_modes[0];
        nbr_flags_ptr = &nbr_flags[0] + 2 * 8 * i;

        for(j = 0; j < num_4x4_blks_x / 2; j++)
        {
            WORD32 i4_best_satd;
            ASSERT(i <= 3);
            ASSERT(j <= 3);

            // Multiply i by 16 since the
            // matrix is prepared for ctb_size = 64
            z_scan_idx = gau1_ctb_raster_to_zscan[i * 2 * 16 + j * 2];
            z_scan_act_idx = gau1_ctb_raster_to_zscan[i * 16 + j];
            ASSERT(z_scan_act_idx <= 15);

            ps_ed_8x8 = ps_ed_ctb + z_scan_idx;

            ihevce_ed_calc_8x8_blk(
                ps_ed_ctxt,
                ps_ed_8x8,
                pu1_src_8x8,
                src_stride,
                nbr_flags_ptr,
                top_intra_mode_ptr,
                left_intra_mode_ptr,
                i * 8,
                lambda,
                sad_ptr + z_scan_idx * NUM_MODES,
                &i4_best_satd,
                i4_layer_id,
                ps_ed_ctxt->i4_quality_preset,
                ps_ed_ctxt->i4_slice_type,
                &i4_best_sad_cost_8x8_l1_ipe,
                &i4_best_sad_8x8_l1_ipe,
                &i4_sum_4x4_satd,
                &i4_min_4x4_satd,
                ps_ipe_optimised_function_list,
                ps_cmn_utils_optimised_function_list);

            if(i4_layer_id == 1)
            {
                ps_ed_ctb_l1->i4_best_sad_cost_8x8_l1_ipe[z_scan_act_idx] =
                    i4_best_sad_cost_8x8_l1_ipe;
                ps_ed_ctb_l1->i4_best_sad_8x8_l1_ipe[z_scan_act_idx] = i4_best_sad_8x8_l1_ipe;
                ps_ed_ctb_l1->i4_best_satd_8x8[z_scan_act_idx] = i4_best_satd;
                ps_ed_ctxt->i8_sum_best_satd += i4_best_satd;
                ps_ed_ctxt->i8_sum_sq_best_satd += (i4_best_satd * i4_best_satd);
                //ps_ed_ctb_l1->i4_sum_4x4_satd[z_scan_act_idx] = i4_sum_4x4_satd;
                //ps_ed_ctb_l1->i4_min_4x4_satd[z_scan_act_idx] = i4_min_4x4_satd;
            }

            pu1_src_8x8 += 8;
            //ps_ed_8x8  += 4;
            top_intra_mode_ptr += 2;
            nbr_flags_ptr += 2;
        }
        left_intra_mode_ptr += 2;
    }
}

/*!
******************************************************************************
* \if Function name : ihevce_ed_frame_init \endif
*
* \brief: Initialize frame context for early decision
*
*****************************************************************************
*/
void ihevce_ed_frame_init(void *pv_ed_ctxt, WORD32 i4_layer_no)
{
    ihevce_ed_ctxt_t *ps_ed_ctxt = (ihevce_ed_ctxt_t *)pv_ed_ctxt;

    g_apf_lum_ip[IP_FUNC_MODE_0] = ps_ed_ctxt->ps_func_selector->ihevc_intra_pred_luma_planar_fptr;
    g_apf_lum_ip[IP_FUNC_MODE_1] = ps_ed_ctxt->ps_func_selector->ihevc_intra_pred_luma_dc_fptr;
    g_apf_lum_ip[IP_FUNC_MODE_2] = ps_ed_ctxt->ps_func_selector->ihevc_intra_pred_luma_mode2_fptr;
    g_apf_lum_ip[IP_FUNC_MODE_3TO9] =
        ps_ed_ctxt->ps_func_selector->ihevc_intra_pred_luma_mode_3_to_9_fptr;
    g_apf_lum_ip[IP_FUNC_MODE_10] = ps_ed_ctxt->ps_func_selector->ihevc_intra_pred_luma_horz_fptr;
    g_apf_lum_ip[IP_FUNC_MODE_11TO17] =
        ps_ed_ctxt->ps_func_selector->ihevc_intra_pred_luma_mode_11_to_17_fptr;
    g_apf_lum_ip[IP_FUNC_MODE_18_34] =
        ps_ed_ctxt->ps_func_selector->ihevc_intra_pred_luma_mode_18_34_fptr;
    g_apf_lum_ip[IP_FUNC_MODE_19TO25] =
        ps_ed_ctxt->ps_func_selector->ihevc_intra_pred_luma_mode_19_to_25_fptr;
    g_apf_lum_ip[IP_FUNC_MODE_26] = ps_ed_ctxt->ps_func_selector->ihevc_intra_pred_luma_ver_fptr;
    g_apf_lum_ip[IP_FUNC_MODE_27TO33] =
        ps_ed_ctxt->ps_func_selector->ihevc_intra_pred_luma_mode_27_to_33_fptr;

    if(i4_layer_no == 1)
    {
        ps_ed_ctxt->i8_sum_best_satd = 0;
        ps_ed_ctxt->i8_sum_sq_best_satd = 0;
    }
}

/**
********************************************************************************
*
*  @brief  downscales by 2 in horz and vertical direction, creates output of
*          size wd/2 * ht/2
*
*  @param[in]  pu1_src : source pointer
*  @param[in]  src_stride : source stride
*  @param[out] pu1_dst : destination pointer. Starting of a row.
*  @param[in]  dst_stride : destination stride
*  @param[in]  wd : width
*  @param[in]  ht : height
*  @param[in]  pu1_wkg_mem : working memory (atleast of size CEIL16(wd) * ht))
*  @param[in]  ht_offset : height offset of the block to be scaled
*  @param[in]  block_ht : height of the block to be scaled
*  @param[in]  wd_offset : width offset of the block to be scaled
*  @param[in]  block_wd : width of the block to be scaled
*
*  @return void
*
*  @remarks Assumption made block_ht should me multiple of 2. LANCZOS_SCALER
*
********************************************************************************
*/
void ihevce_scaling_filter_mxn(
    UWORD8 *pu1_src,
    WORD32 src_strd,
    UWORD8 *pu1_scrtch,
    WORD32 scrtch_strd,
    UWORD8 *pu1_dst,
    WORD32 dst_strd,
    WORD32 ht,
    WORD32 wd)
{
#define FILT_TAP_Q 8
#define N_TAPS 7
    const WORD16 i4_ftaps[N_TAPS] = { -18, 0, 80, 132, 80, 0, -18 };
    WORD32 i, j;
    WORD32 tmp;
    UWORD8 *pu1_src_tmp = pu1_src - 3 * src_strd;
    UWORD8 *pu1_scrtch_tmp = pu1_scrtch;

    /* horizontal filtering */
    for(i = -3; i < ht + 2; i++)
    {
        for(j = 0; j < wd; j += 2)
        {
            tmp = (i4_ftaps[3] * pu1_src_tmp[j] +
                   i4_ftaps[2] * (pu1_src_tmp[j - 1] + pu1_src_tmp[j + 1]) +
                   i4_ftaps[1] * (pu1_src_tmp[j + 2] + pu1_src_tmp[j - 2]) +
                   i4_ftaps[0] * (pu1_src_tmp[j + 3] + pu1_src_tmp[j - 3]) +
                   (1 << (FILT_TAP_Q - 1))) >>
                  FILT_TAP_Q;
            pu1_scrtch_tmp[j >> 1] = CLIP_U8(tmp);
        }
        pu1_scrtch_tmp += scrtch_strd;
        pu1_src_tmp += src_strd;
    }
    /* vertical filtering */
    pu1_scrtch_tmp = pu1_scrtch + 3 * scrtch_strd;
    for(i = 0; i < ht; i += 2)
    {
        for(j = 0; j < (wd >> 1); j++)
        {
            tmp =
                (i4_ftaps[3] * pu1_scrtch_tmp[j] +
                 i4_ftaps[2] * (pu1_scrtch_tmp[j + scrtch_strd] + pu1_scrtch_tmp[j - scrtch_strd]) +
                 i4_ftaps[1] *
                     (pu1_scrtch_tmp[j + 2 * scrtch_strd] + pu1_scrtch_tmp[j - 2 * scrtch_strd]) +
                 i4_ftaps[0] *
                     (pu1_scrtch_tmp[j + 3 * scrtch_strd] + pu1_scrtch_tmp[j - 3 * scrtch_strd]) +
                 (1 << (FILT_TAP_Q - 1))) >>
                FILT_TAP_Q;
            pu1_dst[j] = CLIP_U8(tmp);
        }
        pu1_dst += dst_strd;
        pu1_scrtch_tmp += (scrtch_strd << 1);
    }
}

void ihevce_scale_by_2(
    UWORD8 *pu1_src,
    WORD32 src_strd,
    UWORD8 *pu1_dst,
    WORD32 dst_strd,
    WORD32 wd,
    WORD32 ht,
    UWORD8 *pu1_wkg_mem,
    WORD32 ht_offset,
    WORD32 block_ht,
    WORD32 wd_offset,
    WORD32 block_wd,
    FT_COPY_2D *pf_copy_2d,
    FT_SCALING_FILTER_BY_2 *pf_scaling_filter_mxn)
{
#define N_TAPS 7
#define MAX_BLK_SZ (MAX_CTB_SIZE + ((N_TAPS >> 1) << 1))
    UWORD8 au1_cpy[MAX_BLK_SZ * MAX_BLK_SZ];
    UWORD32 cpy_strd = MAX_BLK_SZ;
    UWORD8 *pu1_cpy = au1_cpy + cpy_strd * (N_TAPS >> 1) + (N_TAPS >> 1);

    UWORD8 *pu1_in, *pu1_out;
    WORD32 in_strd, wkg_mem_strd;

    WORD32 row_start, row_end;
    WORD32 col_start, col_end;
    WORD32 i, fun_select;
    WORD32 ht_tmp, wd_tmp;
    FT_SCALING_FILTER_BY_2 *ihevce_scaling_filters[2];

    assert((wd & 1) == 0);
    assert((ht & 1) == 0);
    assert(block_wd <= MAX_CTB_SIZE);
    assert(block_ht <= MAX_CTB_SIZE);

    /* function pointers for filtering different dimensions */
    ihevce_scaling_filters[0] = ihevce_scaling_filter_mxn;
    ihevce_scaling_filters[1] = pf_scaling_filter_mxn;

    /* handle boundary blks */
    col_start = (wd_offset < (N_TAPS >> 1)) ? 1 : 0;
    row_start = (ht_offset < (N_TAPS >> 1)) ? 1 : 0;
    col_end = ((wd_offset + block_wd) > (wd - (N_TAPS >> 1))) ? 1 : 0;
    row_end = ((ht_offset + block_ht) > (ht - (N_TAPS >> 1))) ? 1 : 0;
    if(col_end && (wd % block_wd != 0))
    {
        block_wd = (wd % block_wd);
    }
    if(row_end && (ht % block_ht != 0))
    {
        block_ht = (ht % block_ht);
    }

    /* boundary blks needs to be padded, copy src to tmp buffer */
    if(col_start || col_end || row_end || row_start)
    {
        UWORD8 *pu1_src_tmp = pu1_src + wd_offset + ht_offset * src_strd;

        pu1_cpy -= (3 * (1 - col_start) + cpy_strd * 3 * (1 - row_start));
        pu1_src_tmp -= (3 * (1 - col_start) + src_strd * 3 * (1 - row_start));
        ht_tmp = block_ht + 3 * (1 - row_start) + 3 * (1 - row_end);
        wd_tmp = block_wd + 3 * (1 - col_start) + 3 * (1 - col_end);
        pf_copy_2d(pu1_cpy, cpy_strd, pu1_src_tmp, src_strd, wd_tmp, ht_tmp);
        pu1_in = au1_cpy + cpy_strd * 3 + 3;
        in_strd = cpy_strd;
    }
    else
    {
        pu1_in = pu1_src + wd_offset + ht_offset * src_strd;
        in_strd = src_strd;
    }

    /*top padding*/
    if(row_start)
    {
        UWORD8 *pu1_cpy_tmp = au1_cpy + cpy_strd * 3;

        pu1_cpy = au1_cpy + cpy_strd * (3 - 1);
        memcpy(pu1_cpy, pu1_cpy_tmp, block_wd + 6);
        pu1_cpy -= cpy_strd;
        memcpy(pu1_cpy, pu1_cpy_tmp, block_wd + 6);
        pu1_cpy -= cpy_strd;
        memcpy(pu1_cpy, pu1_cpy_tmp, block_wd + 6);
    }

    /*bottom padding*/
    if(row_end)
    {
        UWORD8 *pu1_cpy_tmp = au1_cpy + cpy_strd * 3 + (block_ht - 1) * cpy_strd;

        pu1_cpy = pu1_cpy_tmp + cpy_strd;
        memcpy(pu1_cpy, pu1_cpy_tmp, block_wd + 6);
        pu1_cpy += cpy_strd;
        memcpy(pu1_cpy, pu1_cpy_tmp, block_wd + 6);
        pu1_cpy += cpy_strd;
        memcpy(pu1_cpy, pu1_cpy_tmp, block_wd + 6);
    }

    /*left padding*/
    if(col_start)
    {
        UWORD8 *pu1_cpy_tmp = au1_cpy + 3;

        pu1_cpy = au1_cpy;
        for(i = 0; i < block_ht + 6; i++)
        {
            pu1_cpy[0] = pu1_cpy[1] = pu1_cpy[2] = pu1_cpy_tmp[0];
            pu1_cpy += cpy_strd;
            pu1_cpy_tmp += cpy_strd;
        }
    }

    /*right padding*/
    if(col_end)
    {
        UWORD8 *pu1_cpy_tmp = au1_cpy + 3 + block_wd - 1;

        pu1_cpy = au1_cpy + 3 + block_wd;
        for(i = 0; i < block_ht + 6; i++)
        {
            pu1_cpy[0] = pu1_cpy[1] = pu1_cpy[2] = pu1_cpy_tmp[0];
            pu1_cpy += cpy_strd;
            pu1_cpy_tmp += cpy_strd;
        }
    }

    wkg_mem_strd = block_wd >> 1;
    pu1_out = pu1_dst + (wd_offset >> 1);
    fun_select = (block_wd % 16 == 0);
    ihevce_scaling_filters[fun_select](
        pu1_in, in_strd, pu1_wkg_mem, wkg_mem_strd, pu1_out, dst_strd, block_ht, block_wd);

    /* Left padding of 16 for 1st block of every row */
    if(wd_offset == 0)
    {
        UWORD8 u1_val;
        WORD32 pad_wd = 16;
        WORD32 pad_ht = block_ht >> 1;
        UWORD8 *dst = pu1_dst;

        for(i = 0; i < pad_ht; i++)
        {
            u1_val = dst[0];
            memset(&dst[-pad_wd], u1_val, pad_wd);
            dst += dst_strd;
        }
    }

    if(wd == wd_offset + block_wd)
    {
        /* Right padding of (16 + (CEIL16(wd/2))-wd/2) for last block of every row */
        /* Right padding is done only after processing of last block of that row is done*/
        UWORD8 u1_val;
        WORD32 pad_wd = 16 + CEIL16((wd >> 1)) - (wd >> 1) + 4;
        WORD32 pad_ht = block_ht >> 1;
        UWORD8 *dst = pu1_dst + (wd >> 1) - 1;

        for(i = 0; i < pad_ht; i++)
        {
            u1_val = dst[0];
            memset(&dst[1], u1_val, pad_wd);
            dst += dst_strd;
        }

        if(ht_offset == 0)
        {
            /* Top padding of 16 is done for 1st row only after we reach end of that row */
            WORD32 pad_wd = dst_strd;
            WORD32 pad_ht = 16;
            UWORD8 *dst = pu1_dst - 16;

            for(i = 1; i <= pad_ht; i++)
            {
                memcpy(dst - (i * dst_strd), dst, pad_wd);
            }
        }

        /* Bottom padding of (16 + (CEIL16(ht/2)) - ht/2) is done only if we have
         reached end of frame */
        if(ht - ht_offset - block_ht == 0)
        {
            WORD32 pad_wd = dst_strd;
            WORD32 pad_ht = 16 + CEIL16((ht >> 1)) - (ht >> 1) + 4;
            UWORD8 *dst = pu1_dst + (((block_ht >> 1) - 1) * dst_strd) - 16;

            for(i = 1; i <= pad_ht; i++)
                memcpy(dst + (i * dst_strd), dst, pad_wd);
        }
    }
}

/*!
******************************************************************************
* \if Function name : ihevce_decomp_pre_intra_process_row \endif
*
* \brief
*    Row level function which down scales a given row by 2 in horz and
*    vertical direction creates output of size wd/2 * ht/2.
*
*  @param[in]  pu1_src : soource pointer
*  @param[in]  src_stride : source stride
*  @param[out] pu1_dst : desitnation pointer
*  @param[in]  dst_stride : destination stride
*  @param[in]  layer_wd : layer width
*  @param[in]  layer_ht : layer height
*  @param[in]  ht_offset : height offset of the block to be scaled
*  @param[in]  block_ht : height of the block to be scaled
*  @param[in]  wd_offset : width offset of the block to be scaled
*  @param[in]  block_wd : width of the block to be scaled
*  @param[in]  num_col_blks : number of col blks in that row
*
* \return None
*
*  @NOTE : When decompositionis done from L1 to L2 pre intra analysis is
*          done on L1
*
*****************************************************************************
*/
void ihevce_decomp_pre_intra_process_row(
    UWORD8 *pu1_src,
    WORD32 src_stride,
    UWORD8 *pu1_dst_decomp,
    WORD32 dst_stride,
    WORD32 layer_wd,
    WORD32 layer_ht,
    UWORD8 *pu1_wkg_mem,
    WORD32 ht_offset,
    WORD32 block_ht,
    WORD32 block_wd,
    WORD32 i4_cu_aligned_pic_wd,
    WORD32 i4_cu_aligned_pic_ht,
    WORD32 num_col_blks,
    WORD32 layer_no,
    ihevce_ed_ctxt_t *ps_ed_ctxt,
    ihevce_ed_blk_t *ps_ed_row,
    ihevce_ed_ctb_l1_t *ps_ed_ctb_l1_row,
    ihevce_8x8_L0_satd_t *ps_layer0_cur_satd,
    ihevce_8x8_L0_mean_t *ps_layer0_cur_mean,
    WORD32 num_4x4_blks_ctb_y,
    WORD32 num_4x4_blks_last_ctb_x,
    WORD32 skip_decomp,
    WORD32 skip_pre_intra,
    WORD32 row_block_no,
    WORD32 i4_enable_noise_detection,
    ctb_analyse_t *ps_ctb_analyse,
    ihevce_ipe_optimised_function_list_t *ps_ipe_optimised_function_list,
    ihevce_cmn_opt_func_t *ps_cmn_utils_optimised_function_list)
{
    WORD32 col_block_no;

    //ihevce_ed_ctxt_t *ps_ed_ctxt = (ihevce_ed_ctxt_t *)pv_ed_ctxt;
    UWORD8 *pu1_src_pre_intra = pu1_src + (ht_offset * src_stride);
    WORD32 num_4x4_blks_in_ctb = block_wd >> 2;
    //WORD32 nbr_flags[64];
    WORD32 *nbr_flags_ptr = &ps_ed_ctxt->ai4_nbr_flags[0];
    WORD32 src_inc_pre_intra = num_4x4_blks_in_ctb * 4;
    WORD32 inc_ctb = 0;
    ihevce_ed_blk_t *ps_ed_ctb = ps_ed_row;
    ihevce_ed_ctb_l1_t *ps_ed_ctb_l1 = ps_ed_ctb_l1_row;
    WORD32 i, j;
    WORD32 do_pre_intra_analysis;
    pf_ed_calc_ctb ed_calc_ctb;
    ctb_analyse_t *ps_ctb_analyse_curr;

    (void)i4_cu_aligned_pic_wd;
    (void)i4_cu_aligned_pic_ht;
    (void)ps_layer0_cur_satd;
    (void)ps_layer0_cur_mean;
    (void)i4_enable_noise_detection;
    /*increment the struct pointer to point to the first CTB of the current row. */
    ps_ctb_analyse_curr = ps_ctb_analyse + row_block_no * num_col_blks;

    //if((num_4x4_blks_ctb_x == num_4x4_blks_ctb_y) && (num_4x4_blks_in_ctb == num_4x4_blks_ctb_x) )
    if(num_4x4_blks_in_ctb == num_4x4_blks_ctb_y)
    {
        ed_calc_ctb = ihevce_ed_calc_ctb;
    }
    else
    {
        ed_calc_ctb = ihevce_ed_calc_incomplete_ctb;
    }

    inc_ctb = num_4x4_blks_in_ctb * num_4x4_blks_in_ctb;

    do_pre_intra_analysis = ((layer_no == 1) || (layer_no == 2)) && (!skip_pre_intra);

    /*
    * For optimal pre intra analysis first block is processed outside
    * the loop.
    */
    if(!skip_decomp)
    {
        ihevce_scale_by_2(
            pu1_src,
            src_stride,
            pu1_dst_decomp,
            dst_stride,
            layer_wd,
            layer_ht,
            pu1_wkg_mem,
            ht_offset,
            block_ht,
            block_wd * 0,
            block_wd,
            ps_cmn_utils_optimised_function_list->pf_copy_2d,
            ps_ipe_optimised_function_list->pf_scaling_filter_mxn);
        /* Disable noise detection */
        ps_ctb_analyse_curr->s_ctb_noise_params.i4_noise_present = 0;

        memset(
            ps_ctb_analyse_curr->s_ctb_noise_params.au1_is_8x8Blk_noisy,
            0,
            sizeof(ps_ctb_analyse_curr->s_ctb_noise_params.au1_is_8x8Blk_noisy));
    }

    /*
    * Pre intra analysis for the first ctb.
    * To analyse any given CTB we need to set the availability flags of the
    * following neighbouring CTB: BL,L,TL,T,TR.
    */
    if(do_pre_intra_analysis)
    {
        /*
        * At the beginning of ctb row set left intra modes to default value.
        */
        for(j = 0; j < num_4x4_blks_ctb_y; j++)
        {
            ps_ed_ctxt->left_ctb_intra_modes[j] = INTRA_DC;
        }

        /*
        * Copy the neighbor flags for a general ctb (ctb inside the frame; not any corners).
        * The table gau4_nbr_flags_8x8_4x4blks generated for 16x16 4x4 blocks(ctb_size = 64).
        * But the same table holds good for other 4x4 blocks 2d arrays(eg 8x8 4x4 blks,4x4 4x4blks).
        * But the flags must be accessed with stride of 16 since the table has been generated for
        * ctb_size = 64. For odd 4x4 2d arrays(eg 3x3 4x4 blks) the flags needs modification.
        * The flags also need modification for corner ctbs.
        */
        memcpy(
            ps_ed_ctxt->ai4_nbr_flags,
            gau4_nbr_flags_8x8_4x4blks,
            sizeof(gau4_nbr_flags_8x8_4x4blks));

        /*
        * Since this is the fist ctb in the ctb row, set left flags unavailable for 1st CTB col
        */
        for(j = 0; j < num_4x4_blks_ctb_y; j++)
        {
            SET_L_UNAVAILABLE(ps_ed_ctxt->ai4_nbr_flags[j * 8]);
            SET_BL_UNAVAILABLE(ps_ed_ctxt->ai4_nbr_flags[j * 8]);
            SET_TL_UNAVAILABLE(ps_ed_ctxt->ai4_nbr_flags[j * 8]);
        }
        /*
        * If this is the fist ctb row, set top flags unavailable.
        */
        if(ht_offset == 0)
        {
            for(j = 0; j < num_4x4_blks_in_ctb; j++)
            {
                SET_T_UNAVAILABLE(ps_ed_ctxt->ai4_nbr_flags[j]);
                SET_TR_UNAVAILABLE(ps_ed_ctxt->ai4_nbr_flags[j]);
                SET_TL_UNAVAILABLE(ps_ed_ctxt->ai4_nbr_flags[j]);
            }
        }

        /* If this is last ctb row,set BL as not available. */
        if(ht_offset + block_ht >= layer_ht)
        {
            for(j = 0; j < num_4x4_blks_in_ctb; j++)
            {
                SET_BL_UNAVAILABLE(ps_ed_ctxt->ai4_nbr_flags[(num_4x4_blks_ctb_y - 1) * 8 + j]);
            }
        }
        col_block_no = 0;
        /* Call intra analysis for the ctb */
        ed_calc_ctb(
            ps_ed_ctxt,
            ps_ed_ctb,
            ps_ed_ctb_l1,
            pu1_src_pre_intra,
            src_stride,
            num_4x4_blks_in_ctb,
            num_4x4_blks_ctb_y,
            nbr_flags_ptr,
            layer_no,
            row_block_no,
            col_block_no,
            ps_ipe_optimised_function_list,
            ps_cmn_utils_optimised_function_list

        );

        pu1_src_pre_intra += src_inc_pre_intra;
        ps_ed_ctb += inc_ctb;
        ps_ed_ctb_l1 += 1;
        /*
        * For the rest of the ctbs, set left flags available.
        */
        for(j = 0; j < num_4x4_blks_ctb_y; j++)
        {
            SET_L_AVAILABLE(ps_ed_ctxt->ai4_nbr_flags[j * 8]);
        }
        for(j = 0; j < num_4x4_blks_ctb_y - 1; j++)
        {
            SET_BL_AVAILABLE(ps_ed_ctxt->ai4_nbr_flags[j * 8]);
            SET_TL_AVAILABLE(ps_ed_ctxt->ai4_nbr_flags[(j + 1) * 8]);
        }
        if(ht_offset != 0)
        {
            SET_TL_AVAILABLE(ps_ed_ctxt->ai4_nbr_flags[0]);
        }
    }

    /* The first ctb is processed before the loop.
    * The last one is processed after the loop.
    */
    for(col_block_no = 1; col_block_no < num_col_blks - 1; col_block_no++)
    {
        if(!skip_decomp)
        {
            ihevce_scale_by_2(
                pu1_src,
                src_stride,
                pu1_dst_decomp,
                dst_stride,
                layer_wd,
                layer_ht,
                pu1_wkg_mem,
                ht_offset,
                block_ht,
                block_wd * col_block_no,
                block_wd,
                ps_cmn_utils_optimised_function_list->pf_copy_2d,
                ps_ipe_optimised_function_list->pf_scaling_filter_mxn);
            /* Disable noise detection */
            memset(
                ps_ctb_analyse_curr->s_ctb_noise_params.au1_is_8x8Blk_noisy,
                0,
                sizeof(ps_ctb_analyse_curr->s_ctb_noise_params.au1_is_8x8Blk_noisy));

            ps_ctb_analyse_curr->s_ctb_noise_params.i4_noise_present = 0;
        }

        if(do_pre_intra_analysis)
        {
            ed_calc_ctb(
                ps_ed_ctxt,
                ps_ed_ctb,
                ps_ed_ctb_l1,
                pu1_src_pre_intra,
                src_stride,
                num_4x4_blks_in_ctb,
                num_4x4_blks_ctb_y,
                nbr_flags_ptr,
                layer_no,
                row_block_no,
                col_block_no,
                ps_ipe_optimised_function_list,
                ps_cmn_utils_optimised_function_list);
            pu1_src_pre_intra += src_inc_pre_intra;
            ps_ed_ctb += inc_ctb;
            ps_ed_ctb_l1 += 1;
        }
    }

    /* Last ctb in row */
    if((!skip_decomp) && (col_block_no == (num_col_blks - 1)))
    {
        ihevce_scale_by_2(
            pu1_src,
            src_stride,
            pu1_dst_decomp,
            dst_stride,
            layer_wd,
            layer_ht,
            pu1_wkg_mem,
            ht_offset,
            block_ht,
            block_wd * col_block_no,
            block_wd,
            ps_cmn_utils_optimised_function_list->pf_copy_2d,
            ps_ipe_optimised_function_list->pf_scaling_filter_mxn);
        {
            /* Disable noise detection */
            memset(
                ps_ctb_analyse_curr->s_ctb_noise_params.au1_is_8x8Blk_noisy,
                0,
                sizeof(ps_ctb_analyse_curr->s_ctb_noise_params.au1_is_8x8Blk_noisy));

            ps_ctb_analyse_curr->s_ctb_noise_params.i4_noise_present = 0;
        }
    }

    if(do_pre_intra_analysis && (col_block_no == (num_col_blks - 1)))
    {
        /*
        * The last ctb can be complete or incomplete. The complete
        * ctb is handled in the if and incomplete is handled in the
        * else case
        */
        //if(num_4x4_blks_last_ctb == num_4x4_blks_in_ctb)
        if((num_4x4_blks_last_ctb_x == num_4x4_blks_ctb_y) &&
           (num_4x4_blks_in_ctb == num_4x4_blks_last_ctb_x))
        {
            /* Last ctb so set top right not available */
            SET_TR_UNAVAILABLE(ps_ed_ctxt->ai4_nbr_flags[num_4x4_blks_in_ctb - 1]);

            ed_calc_ctb(
                ps_ed_ctxt,
                ps_ed_ctb,
                ps_ed_ctb_l1,
                pu1_src_pre_intra,
                src_stride,
                num_4x4_blks_in_ctb,
                num_4x4_blks_in_ctb,
                nbr_flags_ptr,
                layer_no,
                row_block_no,
                col_block_no,
                ps_ipe_optimised_function_list,
                ps_cmn_utils_optimised_function_list);
            pu1_src_pre_intra += src_inc_pre_intra;
            ps_ed_ctb += inc_ctb;
            ps_ed_ctb_l1 += 1;
        }
        else
        {
            /* Last ctb so set top right not available */
            for(i = 0; i < num_4x4_blks_ctb_y; i++)
            {
                SET_TR_UNAVAILABLE(ps_ed_ctxt->ai4_nbr_flags[i * 8 + num_4x4_blks_in_ctb - 1]);
            }

            ihevce_ed_calc_incomplete_ctb(
                ps_ed_ctxt,
                ps_ed_ctb,
                ps_ed_ctb_l1,
                pu1_src_pre_intra,
                src_stride,
                num_4x4_blks_last_ctb_x,
                num_4x4_blks_ctb_y,
                nbr_flags_ptr,
                layer_no,
                row_block_no,
                col_block_no,
                ps_ipe_optimised_function_list,
                ps_cmn_utils_optimised_function_list);
        }
    }
}

/*!
******************************************************************************
* \if Function name : ihevce_decomp_pre_intra_process \endif
*
* \brief
*    Frame level function to decompose given layer L0 into coarser layers
*
* \param[in] pv_ctxt : pointer to master context of decomp_pre_intra module
* \param[in] ps_inp  : pointer to input yuv buffer (frame buffer)
* \param[in] pv_multi_thrd_ctxt : pointer to multithread context
* \param[out] thrd_id : thread id
*
* \return
*    None
*
* \author
*  Ittiam
*
*****************************************************************************
*/
void ihevce_decomp_pre_intra_process(
    void *pv_ctxt,
    ihevce_lap_output_params_t *ps_lap_out_prms,
    frm_ctb_ctxt_t *ps_frm_ctb_prms,
    void *pv_multi_thrd_ctxt,
    WORD32 thrd_id,
    WORD32 i4_ping_pong,
    ihevce_8x8_L0_satd_t *ps_layer0_cur_satd,
    ihevce_8x8_L0_mean_t *ps_layer0_cur_mean)
{
    WORD32 i4_layer_no;
    WORD32 i4_num_layers;
    WORD32 end_of_layer;
    UWORD8 *pu1_src, *pu1_dst;
    WORD32 src_stride, dst_stride;
    WORD32 i4_layer_wd, i4_layer_ht;
    WORD32 ht_offset, block_ht;
    WORD32 row_block_no, num_row_blocks;
    UWORD8 *pu1_wkg_mem;
    WORD32 block_wd;
    WORD32 num_col_blks;
    WORD32 skip_decomp, skip_pre_intra;
    WORD32 i4_cu_aligned_pic_wd, i4_cu_aligned_pic_ht;
    ihevce_decomp_pre_intra_master_ctxt_t *ps_master_ctxt =
        (ihevce_decomp_pre_intra_master_ctxt_t *)pv_ctxt;

    ihevce_decomp_pre_intra_ctxt_t *ps_ctxt =
        ps_master_ctxt->aps_decomp_pre_intra_thrd_ctxt[thrd_id];
    multi_thrd_ctxt_t *ps_multi_thrd = (multi_thrd_ctxt_t *)pv_multi_thrd_ctxt;

    ihevce_ed_ctxt_t *ps_ed_ctxt;
    ihevce_ed_blk_t *ps_ed;
    ihevce_ed_ctb_l1_t *ps_ed_ctb_l1;
    WORD32 inc_ctb = 0;
    WORD32 num_4x4_blks_lyr;

    i4_num_layers = ps_ctxt->i4_num_layers;

    ASSERT(i4_num_layers >= 3);

    /*
     * Always force minimum layers as 4 so that we would have both l1 and l2
     * pre intra analysis
     */
    if(i4_num_layers == 3)
    {
        i4_num_layers = 4;
    }

    ps_ctxt->as_layers[0].pu1_inp = (UWORD8 *)ps_lap_out_prms->s_input_buf.pv_y_buf;
    ps_ctxt->as_layers[0].i4_inp_stride = ps_lap_out_prms->s_input_buf.i4_y_strd;
    ps_ctxt->as_layers[0].i4_actual_wd = ps_lap_out_prms->s_input_buf.i4_y_wd;
    ps_ctxt->as_layers[0].i4_actual_ht = ps_lap_out_prms->s_input_buf.i4_y_ht;

    /* ------------ Loop over all the layers --------------- */
    /* This loop does only decomp for all layers by picking jobs from job queue */
    /* Decomp for all layers will completed with this for loop */
    for(i4_layer_no = 0; i4_layer_no < (i4_num_layers - 1); i4_layer_no++)
    {
        WORD32 idx = 0;
        src_stride = ps_ctxt->as_layers[i4_layer_no].i4_inp_stride;
        pu1_src = ps_ctxt->as_layers[i4_layer_no].pu1_inp;
        i4_layer_wd = ps_ctxt->as_layers[i4_layer_no].i4_actual_wd;
        i4_layer_ht = ps_ctxt->as_layers[i4_layer_no].i4_actual_ht;
        pu1_dst = ps_ctxt->as_layers[i4_layer_no + 1].pu1_inp;
        dst_stride = ps_ctxt->as_layers[i4_layer_no + 1].i4_inp_stride;
        block_wd = ps_ctxt->as_layers[i4_layer_no].i4_decomp_blk_wd;
        block_ht = ps_ctxt->as_layers[i4_layer_no].i4_decomp_blk_ht;
        num_col_blks = ps_ctxt->as_layers[i4_layer_no].i4_num_col_blks;
        num_row_blocks = ps_ctxt->as_layers[i4_layer_no].i4_num_row_blks;
        i4_cu_aligned_pic_wd = ps_frm_ctb_prms->i4_cu_aligned_pic_wd;
        i4_cu_aligned_pic_ht = ps_frm_ctb_prms->i4_cu_aligned_pic_ht;

        /* register ed_ctxt buffer pointer */
        //pv_ed_ctxt =  &ps_ctxt->as_layers[i4_layer_no].s_early_decision;
        //ps_ed_ctxt = (ihevce_ed_ctxt_t *)pv_ed_ctxt;
        //ps_ed = ps_ed_ctxt->ps_ed;

        //pv_ed_ctxt = &ps_ctxt->ps_ed_ctxt;
        ps_ed_ctxt = ps_ctxt->ps_ed_ctxt;

        /* initialize ed_ctxt here */
        /* init is moved here since now allocation is happening for only one instance
        is allocated. for each layer it is re-used */
        ps_ed_ctxt->lambda = ps_ctxt->ai4_lambda[i4_layer_no];
        ps_ed_ctxt->i4_slice_type = ps_ctxt->i4_slice_type;
        ps_ed_ctxt->level = ps_ctxt->i4_codec_level;
        if(0 == i4_layer_no)
        {
            ps_ed_ctxt->ps_ed_pic = NULL;
            ps_ed_ctxt->ps_ed = NULL;
            ps_ed_ctxt->ps_ed_ctb_l1_pic = NULL;
            ps_ed_ctxt->ps_ed_ctb_l1 = NULL;
        }
        else if(1 == i4_layer_no)
        {
            ps_ed_ctxt->ps_ed_pic = ps_ctxt->ps_layer1_buf;
            ps_ed_ctxt->ps_ed = ps_ctxt->ps_layer1_buf;
            ps_ed_ctxt->ps_ed_ctb_l1_pic = ps_ctxt->ps_ed_ctb_l1;
            ps_ed_ctxt->ps_ed_ctb_l1 = ps_ctxt->ps_ed_ctb_l1;
            ps_ctxt->ps_layer0_cur_satd = NULL;
            ps_ctxt->ps_layer0_cur_mean = NULL;
        }
        else if(2 == i4_layer_no)
        {
            ps_ed_ctxt->ps_ed_pic = ps_ctxt->ps_layer2_buf;
            ps_ed_ctxt->ps_ed = ps_ctxt->ps_layer2_buf;
            ps_ed_ctxt->ps_ed_ctb_l1_pic = NULL;
            ps_ed_ctxt->ps_ed_ctb_l1 = NULL;
            ps_ctxt->ps_layer0_cur_satd = NULL;
            ps_ctxt->ps_layer0_cur_mean = NULL;
        }

        /*Calculate the number of 4x4 blocks in a CTB in that layer*/
        /*Divide block_wd by 4. 4 to get no of 4x4 blks*/
        num_4x4_blks_lyr = block_wd >> 2;
        inc_ctb = num_4x4_blks_lyr * num_4x4_blks_lyr;

        ps_ed = ps_ed_ctxt->ps_ed;
        ps_ed_ctb_l1 = ps_ed_ctxt->ps_ed_ctb_l1;

        end_of_layer = 0;
        skip_decomp = 0;
        skip_pre_intra = 1;
        //if( i4_layer_no >= ps_ctxt->i4_num_layers)
        if(i4_layer_no >= (ps_ctxt->i4_num_layers - 1))
        {
            skip_decomp = 1;
        }
        /* ------------ Loop over all the CTB rows --------------- */
        while(0 == end_of_layer)
        {
            job_queue_t *ps_pre_enc_job;
            WORD32 num_4x4_blks_ctb_y = 0;
            WORD32 num_4x4_blks_last_ctb_x = 0;

            /* Get the current row from the job queue */
            ps_pre_enc_job = (job_queue_t *)ihevce_pre_enc_grp_get_next_job(
                pv_multi_thrd_ctxt, (DECOMP_JOB_LYR0 + i4_layer_no), 1, i4_ping_pong);

            pu1_wkg_mem = ps_ctxt->pu1_wkg_mem;

            /* If all rows are done, set the end of layer flag to 1, */
            if(NULL == ps_pre_enc_job)
            {
                end_of_layer = 1;
            }
            else
            {
                /* Obtain the current row's details from the job */
                row_block_no = ps_pre_enc_job->s_job_info.s_decomp_job_info.i4_vert_unit_row_no;
                ps_ctxt->as_layers[i4_layer_no].ai4_curr_row_no[idx] = row_block_no;
                ht_offset = row_block_no * block_ht;

                if(row_block_no < (num_row_blocks))
                {
                    pu1_dst = ps_ctxt->as_layers[i4_layer_no + 1].pu1_inp +
                              ((block_ht >> 1) * dst_stride * row_block_no);

                    /*L0 8x8 curr satd for qp mod*/
                    if(i4_layer_no == 0)
                    {
                        ps_ctxt->ps_layer0_cur_satd =
                            ps_layer0_cur_satd + (row_block_no * num_col_blks /*num ctbs*/ *
                                                  (block_wd >> 3) * (block_ht >> 3));
                        ps_ctxt->ps_layer0_cur_mean =
                            ps_layer0_cur_mean + (row_block_no * num_col_blks /*num ctbs*/ *
                                                  (block_wd >> 3) * (block_ht >> 3));
                    }

                    /* call the row level processing function */
                    ihevce_decomp_pre_intra_process_row(
                        pu1_src,
                        src_stride,
                        pu1_dst,
                        dst_stride,
                        i4_layer_wd,
                        i4_layer_ht,
                        pu1_wkg_mem,
                        ht_offset,
                        block_ht,
                        block_wd,
                        i4_cu_aligned_pic_wd,
                        i4_cu_aligned_pic_ht,
                        num_col_blks,
                        i4_layer_no,
                        ps_ed_ctxt,
                        ps_ed,
                        ps_ed_ctb_l1,
                        ps_ctxt->ps_layer0_cur_satd,
                        ps_ctxt->ps_layer0_cur_mean,
                        num_4x4_blks_ctb_y,
                        num_4x4_blks_last_ctb_x,
                        skip_decomp,
                        skip_pre_intra,
                        row_block_no,
                        ps_ctxt->i4_enable_noise_detection,
                        ps_ctxt->ps_ctb_analyse,
                        &ps_ctxt->s_ipe_optimised_function_list,
                        &ps_ctxt->s_cmn_opt_func);

                    /*When decompositionis done from L1 to L2
                    pre intra analysis is done on L1*/
                    if(i4_layer_no == 1 || i4_layer_no == 2)
                    {
                        // ps_ed   = ps_ed_ctxt->ps_ed +
                        //          (row_block_no * inc_ctb * (num_col_blks));
                    }
                }
                idx++;
                /* set the output dependency */
                ihevce_pre_enc_grp_job_set_out_dep(
                    pv_multi_thrd_ctxt, ps_pre_enc_job, i4_ping_pong);
            }
        }
        ps_ctxt->as_layers[i4_layer_no].i4_num_rows_processed = idx;

        ihevce_ed_frame_init(ps_ed_ctxt, i4_layer_no);

        if((1 == i4_layer_no) && (IHEVCE_QUALITY_P6 == ps_ctxt->i4_quality_preset))
        {
            WORD32 vert_ctr, ctb_ctr, i;
            WORD32 ctb_ctr_blks = ps_ctxt->as_layers[1].i4_num_col_blks;
            WORD32 vert_ctr_blks = ps_ctxt->as_layers[1].i4_num_row_blks;

            if((ps_ctxt->i4_quality_preset == IHEVCE_QUALITY_P6) &&
               (ps_lap_out_prms->i4_temporal_lyr_id > TEMPORAL_LAYER_DISABLE))
            {
                for(vert_ctr = 0; vert_ctr < vert_ctr_blks; vert_ctr++)
                {
                    ihevce_ed_ctb_l1_t *ps_ed_ctb_row_l1 =
                        ps_ctxt->ps_ed_ctb_l1 + vert_ctr * ps_frm_ctb_prms->i4_num_ctbs_horz;

                    for(ctb_ctr = 0; ctb_ctr < ctb_ctr_blks; ctb_ctr++)
                    {
                        ihevce_ed_ctb_l1_t *ps_ed_ctb_curr_l1 = ps_ed_ctb_row_l1 + ctb_ctr;
                        for(i = 0; i < 16; i++)
                        {
                            ps_ed_ctb_curr_l1->i4_best_sad_cost_8x8_l1_ipe[i] = 0x7fffffff;
                            ps_ed_ctb_curr_l1->i4_best_sad_8x8_l1_ipe[i] = 0x7fffffff;
                        }
                    }
                }
            }
        }

#if DISABLE_L2_IPE_IN_PB_L1_IN_B
        if(((2 == i4_layer_no) && (ps_lap_out_prms->i4_pic_type == IV_I_FRAME ||
                                   ps_lap_out_prms->i4_pic_type == IV_IDR_FRAME)) ||
           ((1 == i4_layer_no) &&
            (ps_lap_out_prms->i4_temporal_lyr_id <= TEMPORAL_LAYER_DISABLE)) ||
           ((IHEVCE_QUALITY_P6 != ps_ctxt->i4_quality_preset) && (0 != i4_layer_no)))
#else
        if((0 != i4_layer_no) &&
           (1 != ((IHEVCE_QUALITY_P6 == ps_ctxt->i4_quality_preset) &&
                  (ps_lap_out_prms->i4_temporal_lyr_id > TEMPORAL_LAYER_DISABLE))))
#endif
        {
            WORD32 i4_num_rows = ps_ctxt->as_layers[i4_layer_no].i4_num_rows_processed;

            src_stride = ps_ctxt->as_layers[i4_layer_no].i4_inp_stride;
            pu1_src = ps_ctxt->as_layers[i4_layer_no].pu1_inp;
            i4_layer_wd = ps_ctxt->as_layers[i4_layer_no].i4_actual_wd;
            i4_layer_ht = ps_ctxt->as_layers[i4_layer_no].i4_actual_ht;
            pu1_dst = ps_ctxt->as_layers[i4_layer_no + 1].pu1_inp;
            dst_stride = ps_ctxt->as_layers[i4_layer_no + 1].i4_inp_stride;
            block_wd = ps_ctxt->as_layers[i4_layer_no].i4_decomp_blk_wd;
            block_ht = ps_ctxt->as_layers[i4_layer_no].i4_decomp_blk_ht;
            num_col_blks = ps_ctxt->as_layers[i4_layer_no].i4_num_col_blks;
            num_row_blocks = ps_ctxt->as_layers[i4_layer_no].i4_num_row_blks;
            i4_cu_aligned_pic_wd = ps_frm_ctb_prms->i4_cu_aligned_pic_wd;
            i4_cu_aligned_pic_ht = ps_frm_ctb_prms->i4_cu_aligned_pic_ht;

            /* register ed_ctxt buffer pointer */
            ps_ed_ctxt = ps_ctxt->ps_ed_ctxt;

            /* initialize ed_ctxt here */
            /* init is moved here since now allocation is happening for only one instance
            is allocated. for each layer it is re-used */
            ps_ed_ctxt->lambda = ps_ctxt->ai4_lambda[i4_layer_no];
            ps_ed_ctxt->i4_slice_type = ps_ctxt->i4_slice_type;
            ps_ed_ctxt->level = ps_ctxt->i4_codec_level;
            if(1 == i4_layer_no)
            {
                ps_ed_ctxt->ps_ed_pic = ps_ctxt->ps_layer1_buf;
                ps_ed_ctxt->ps_ed = ps_ctxt->ps_layer1_buf;
                ps_ed_ctxt->ps_ed_ctb_l1_pic = ps_ctxt->ps_ed_ctb_l1;
                ps_ed_ctxt->ps_ed_ctb_l1 = ps_ctxt->ps_ed_ctb_l1;
                ps_ctxt->ps_layer0_cur_satd = NULL;
                ps_ctxt->ps_layer0_cur_mean = NULL;
            }
            else if(2 == i4_layer_no)
            {
                ps_ed_ctxt->ps_ed_pic = ps_ctxt->ps_layer2_buf;
                ps_ed_ctxt->ps_ed = ps_ctxt->ps_layer2_buf;
                ps_ed_ctxt->ps_ed_ctb_l1_pic = NULL;
                ps_ed_ctxt->ps_ed_ctb_l1 = NULL;
                ps_ctxt->ps_layer0_cur_satd = NULL;
                ps_ctxt->ps_layer0_cur_mean = NULL;
            }

            /*Calculate the number of 4x4 blocks in a CTB in that layer*/
            /*Divide block_wd by 4. 4 to get no of 4x4 blks*/
            num_4x4_blks_lyr = block_wd >> 2;
            inc_ctb = num_4x4_blks_lyr * num_4x4_blks_lyr;

            ps_ed = ps_ed_ctxt->ps_ed;
            ps_ed_ctb_l1 = ps_ed_ctxt->ps_ed_ctb_l1;
            skip_decomp = 1;
            skip_pre_intra = 0;
            for(idx = 0; idx < i4_num_rows; idx++)
            {
                WORD32 num_4x4_blks_ctb_y = 0;
                WORD32 num_4x4_blks_last_ctb_x = 0;

                pu1_wkg_mem = ps_ctxt->pu1_wkg_mem;

                {
                    /* Obtain the current row's details from the job */
                    row_block_no = ps_ctxt->as_layers[i4_layer_no].ai4_curr_row_no[idx];
                    ht_offset = row_block_no * block_ht;

                    if(row_block_no < (num_row_blocks))
                    {
                        pu1_dst = ps_ctxt->as_layers[i4_layer_no + 1].pu1_inp +
                                  ((block_ht >> 1) * dst_stride * row_block_no);

                        if(i4_layer_no == 1 || i4_layer_no == 2)
                        {
                            ps_ed = ps_ed_ctxt->ps_ed + (row_block_no * inc_ctb * (num_col_blks));
                            ps_ed_ctb_l1 = ps_ed_ctxt->ps_ed_ctb_l1 + (row_block_no * num_col_blks);

                            ps_ed_ctxt->i4_quality_preset = ps_ctxt->i4_quality_preset;
                            num_4x4_blks_ctb_y = block_ht >> 2;
                            num_4x4_blks_last_ctb_x = block_wd >> 2;

                            if(row_block_no == num_row_blocks - 1)
                            {
                                if(i4_layer_ht % block_ht)
                                {
                                    num_4x4_blks_ctb_y = ((i4_layer_ht % block_ht) + 3) >> 2;
                                }
                            }

                            if(i4_layer_wd % block_wd)
                            {
                                num_4x4_blks_last_ctb_x = ((i4_layer_wd % block_wd) + 3) >> 2;
                            }
                        }

                        /* call the row level processing function */
                        ihevce_decomp_pre_intra_process_row(
                            pu1_src,
                            src_stride,
                            pu1_dst,
                            dst_stride,
                            i4_layer_wd,
                            i4_layer_ht,
                            pu1_wkg_mem,
                            ht_offset,
                            block_ht,
                            block_wd,
                            i4_cu_aligned_pic_wd,
                            i4_cu_aligned_pic_ht,
                            num_col_blks,
                            i4_layer_no,
                            ps_ed_ctxt,
                            ps_ed,
                            ps_ed_ctb_l1,
                            ps_ctxt->ps_layer0_cur_satd,
                            ps_ctxt->ps_layer0_cur_mean,
                            num_4x4_blks_ctb_y,
                            num_4x4_blks_last_ctb_x,
                            skip_decomp,
                            skip_pre_intra,
                            row_block_no,
                            0,
                            NULL,
                            &ps_ctxt->s_ipe_optimised_function_list,
                            &ps_ctxt->s_cmn_opt_func);
                    }
                }
                if(1 == i4_layer_no)
                {
                    ps_multi_thrd->aai4_l1_pre_intra_done[i4_ping_pong][row_block_no] = 1;
                }
            }
            for(idx = 0; idx < MAX_NUM_CTB_ROWS_FRM; idx++)
            {
                ps_ctxt->as_layers[i4_layer_no].ai4_curr_row_no[idx] = -1;
            }
            ps_ctxt->as_layers[i4_layer_no].i4_num_rows_processed = 0;
        }

#if DISABLE_L2_IPE_IN_PB_L1_IN_B
        if((IHEVCE_QUALITY_P6 == ps_ctxt->i4_quality_preset) &&
           (((i4_layer_no == 2) && (ps_lap_out_prms->i4_pic_type == ISLICE)) ||
            ((i4_layer_no == 1) && (ps_lap_out_prms->i4_temporal_lyr_id > TEMPORAL_LAYER_DISABLE))))
        {
            WORD32 i4_num_rows = ps_ctxt->as_layers[i4_layer_no].i4_num_rows_processed;
            if(1 == i4_layer_no)
            {
                for(idx = 0; idx < i4_num_rows; idx++)
                {
                    row_block_no = ps_ctxt->as_layers[i4_layer_no].ai4_curr_row_no[idx];

                    {
                        ps_multi_thrd->aai4_l1_pre_intra_done[i4_ping_pong][row_block_no] = 1;
                    }
                }
            }
            for(idx = 0; idx < MAX_NUM_CTB_ROWS_FRM; idx++)
            {
                ps_ctxt->as_layers[i4_layer_no].ai4_curr_row_no[idx] = -1;
            }
            ps_ctxt->as_layers[i4_layer_no].i4_num_rows_processed = 0;
        }
#else
        if((i4_layer_no != 0) && ((IHEVCE_QUALITY_P6 == ps_ctxt->i4_quality_preset) &&
                                  (ps_lap_out_prms->i4_temporal_lyr_id > TEMPORAL_LAYER_DISABLE)))
        {
            WORD32 i4_num_rows = ps_ctxt->as_layers[i4_layer_no].i4_num_rows_processed;
            for(idx = 0; idx < i4_num_rows; idx++)
            {
                row_block_no = ps_ctxt->as_layers[i4_layer_no].ai4_curr_row_no[idx];
                if(1 == i4_layer_no)
                {
                    ps_multi_thrd->aai4_l1_pre_intra_done[i4_ping_pong][row_block_no] = 1;
                }
            }
            for(idx = 0; idx < MAX_NUM_CTB_ROWS_FRM; idx++)
            {
                ps_ctxt->as_layers[i4_layer_no].ai4_curr_row_no[idx] = -1;
            }
            ps_ctxt->as_layers[i4_layer_no].i4_num_rows_processed = 0;
        }
#endif
    }
}

/*!
************************************************************************
* \brief
*    return number of records used by decomp pre intra
*
************************************************************************
*/
WORD32 ihevce_decomp_pre_intra_get_num_mem_recs(void)
{
    return (NUM_DECOMP_PRE_INTRA_MEM_RECS);
}

/*!
************************************************************************
* @brief
*    return each record attributes of  decomp pre intra
************************************************************************
*/
WORD32 ihevce_decomp_pre_intra_get_mem_recs(
    iv_mem_rec_t *ps_mem_tab, WORD32 i4_num_proc_thrds, WORD32 i4_mem_space)
{
    /* memories should be requested assuming worst case requirememnts */

    /* Module context structure */
    ps_mem_tab[DECOMP_PRE_INTRA_CTXT].i4_mem_size = sizeof(ihevce_decomp_pre_intra_master_ctxt_t);
    ps_mem_tab[DECOMP_PRE_INTRA_CTXT].e_mem_type = (IV_MEM_TYPE_T)i4_mem_space;
    ps_mem_tab[DECOMP_PRE_INTRA_CTXT].i4_mem_alignment = 8;

    /* Thread context structure */
    ps_mem_tab[DECOMP_PRE_INTRA_THRDS_CTXT].i4_mem_size =
        i4_num_proc_thrds * sizeof(ihevce_decomp_pre_intra_ctxt_t);
    ps_mem_tab[DECOMP_PRE_INTRA_THRDS_CTXT].e_mem_type = (IV_MEM_TYPE_T)i4_mem_space;
    ps_mem_tab[DECOMP_PRE_INTRA_THRDS_CTXT].i4_mem_alignment = 8;

    /* early decision context structure */
    ps_mem_tab[DECOMP_PRE_INTRA_ED_CTXT].i4_mem_size = i4_num_proc_thrds * sizeof(ihevce_ed_ctxt_t);
    ps_mem_tab[DECOMP_PRE_INTRA_ED_CTXT].e_mem_type = (IV_MEM_TYPE_T)i4_mem_space;
    ps_mem_tab[DECOMP_PRE_INTRA_ED_CTXT].i4_mem_alignment = 8;

    return (NUM_DECOMP_PRE_INTRA_MEM_RECS);
}

/*!
************************************************************************
* @brief
*    Init decomp pre intra context
************************************************************************
*/
void *ihevce_decomp_pre_intra_init(
    iv_mem_rec_t *ps_mem_tab,
    ihevce_static_cfg_params_t *ps_init_prms,
    WORD32 i4_num_proc_thrds,
    func_selector_t *ps_func_selector,
    WORD32 i4_resolution_id,
    UWORD8 u1_is_popcnt_available)
{
    ihevce_decomp_pre_intra_master_ctxt_t *ps_master_ctxt;
    ihevce_decomp_pre_intra_ctxt_t *ps_ctxt;
    WORD32 thread_no;
    WORD32 n_tot_layers;
    WORD32 count;
    WORD32 a_wd[MAX_NUM_HME_LAYERS], a_ht[MAX_NUM_HME_LAYERS], layer_no;
    WORD32 a_disp_wd[MAX_NUM_LAYERS], a_disp_ht[MAX_NUM_LAYERS];
    ihevce_ed_ctxt_t *ps_ed_ctxt;
    WORD32 min_cu_size;

    /* get the min cu size from config params */
    min_cu_size = ps_init_prms->s_config_prms.i4_min_log2_cu_size;

    min_cu_size = 1 << min_cu_size;

    /* Get the height and width of each layer */
    *a_wd = ps_init_prms->s_tgt_lyr_prms.as_tgt_params[i4_resolution_id].i4_width +
            SET_CTB_ALIGN(
                ps_init_prms->s_tgt_lyr_prms.as_tgt_params[i4_resolution_id].i4_width, min_cu_size);
    *a_ht =
        ps_init_prms->s_tgt_lyr_prms.as_tgt_params[i4_resolution_id].i4_height +
        SET_CTB_ALIGN(
            ps_init_prms->s_tgt_lyr_prms.as_tgt_params[i4_resolution_id].i4_height, min_cu_size);

    n_tot_layers = hme_derive_num_layers(1, a_wd, a_ht, a_disp_wd, a_disp_ht);

    /* Decomp state structure */
    ps_master_ctxt =
        (ihevce_decomp_pre_intra_master_ctxt_t *)ps_mem_tab[DECOMP_PRE_INTRA_CTXT].pv_base;
    ps_master_ctxt->i4_num_proc_thrds = i4_num_proc_thrds;

    ps_ctxt = (ihevce_decomp_pre_intra_ctxt_t *)ps_mem_tab[DECOMP_PRE_INTRA_THRDS_CTXT].pv_base;
    ps_ed_ctxt = (ihevce_ed_ctxt_t *)ps_mem_tab[DECOMP_PRE_INTRA_ED_CTXT].pv_base;

    for(thread_no = 0; thread_no < ps_master_ctxt->i4_num_proc_thrds; thread_no++)
    {
        ps_master_ctxt->aps_decomp_pre_intra_thrd_ctxt[thread_no] = ps_ctxt;

        ps_master_ctxt->aps_decomp_pre_intra_thrd_ctxt[thread_no]->i4_num_layers = n_tot_layers;

        ps_master_ctxt->aps_decomp_pre_intra_thrd_ctxt[thread_no]->pu1_wkg_mem =
            &ps_master_ctxt->aps_decomp_pre_intra_thrd_ctxt[thread_no]->au1_wkg_mem[0];

        ps_master_ctxt->aps_decomp_pre_intra_thrd_ctxt[thread_no]->ps_ed_ctxt = ps_ed_ctxt;

        for(layer_no = 0; layer_no < n_tot_layers; layer_no++)
        {
            WORD32 max_ctb_size;
            WORD32 decomp_blk_ht, decomp_blk_wd;

            ps_ctxt->as_layers[layer_no].i4_actual_wd = a_wd[layer_no];
            ps_ctxt->as_layers[layer_no].i4_actual_ht = a_ht[layer_no];
            ps_ctxt->as_layers[layer_no].i4_inp_stride = 0;
            ps_ctxt->as_layers[layer_no].pu1_inp = NULL;
            ps_ctxt->as_layers[layer_no].i4_num_rows_processed = 0;

            for(count = 0; count < MAX_NUM_CTB_ROWS_FRM; count++)
            {
                ps_ctxt->as_layers[layer_no].ai4_curr_row_no[count] = -1;
            }
            if(0 == layer_no)
            {
                ps_ctxt->as_layers[layer_no].i4_padded_ht = a_ht[layer_no];
                ps_ctxt->as_layers[layer_no].i4_padded_wd = a_wd[layer_no];
            }
            else
            {
                ps_ctxt->as_layers[layer_no].i4_padded_ht = a_ht[layer_no] + 32 + 4;
                ps_ctxt->as_layers[layer_no].i4_padded_wd = a_wd[layer_no] + 32 + 4;
            }

            /** If CTB size= 64.decomp_blk_wd = 64 for L0, 32 for L1 , 16 for L2, 8 for L3 */
            max_ctb_size = 1 << ps_init_prms->s_config_prms.i4_max_log2_cu_size;

            ps_ctxt->as_layers[layer_no].i4_decomp_blk_ht = max_ctb_size >> layer_no;
            ps_ctxt->as_layers[layer_no].i4_decomp_blk_wd = max_ctb_size >> layer_no;

            decomp_blk_ht = ps_ctxt->as_layers[layer_no].i4_decomp_blk_ht;
            decomp_blk_wd = ps_ctxt->as_layers[layer_no].i4_decomp_blk_wd;

            ps_ctxt->as_layers[layer_no].i4_num_row_blks =
                ((a_ht[layer_no] + (decomp_blk_ht - 1)) / decomp_blk_ht);

            ps_ctxt->as_layers[layer_no].i4_num_col_blks =
                ((a_wd[layer_no] + (decomp_blk_wd - 1)) / decomp_blk_wd);
        }
        ps_ed_ctxt->ps_func_selector = ps_func_selector;

        ps_ctxt->i4_quality_preset =
            ps_init_prms->s_tgt_lyr_prms.as_tgt_params[i4_resolution_id].i4_quality_preset;

        if(ps_ctxt->i4_quality_preset == IHEVCE_QUALITY_P7)
        {
            ps_ctxt->i4_quality_preset = IHEVCE_QUALITY_P6;
        }

        if(ps_init_prms->s_coding_tools_prms.i4_vqet &
           (1 << BITPOS_IN_VQ_TOGGLE_FOR_CONTROL_TOGGLER))
        {
            if(ps_init_prms->s_coding_tools_prms.i4_vqet &
               (1 << BITPOS_IN_VQ_TOGGLE_FOR_ENABLING_NOISE_PRESERVATION))
            {
                ps_ctxt->i4_enable_noise_detection = 1;
            }
            else
            {
                ps_ctxt->i4_enable_noise_detection = 0;
            }
        }
        else
        {
            ps_ctxt->i4_enable_noise_detection = 0;
        }

        ihevce_cmn_utils_instr_set_router(
            &ps_ctxt->s_cmn_opt_func, u1_is_popcnt_available, ps_init_prms->e_arch_type);

        ihevce_ipe_instr_set_router(
            &ps_ctxt->s_ipe_optimised_function_list, ps_init_prms->e_arch_type);

        ps_ctxt++;
        ps_ed_ctxt++;
    }
    /* return the handle to caller */
    return ((void *)ps_master_ctxt);
}

/*!
******************************************************************************
* \if Function name : ihevce_decomp_pre_intra_frame_init \endif
*
* \brief
*    Frame Intialization for Decomp intra pre analysis.
*
* \param[in] pv_ctxt : pointer to module ctxt
* \param[in] ppu1_decomp_lyr_bufs : pointer to array of layer buffer pointers
* \param[in] pi4_lyr_buf_stride : pointer to array of layer buffer strides
*
* \return
*    None
*
* \author
*  Ittiam
*
*****************************************************************************
*/
void ihevce_decomp_pre_intra_frame_init(
    void *pv_ctxt,
    UWORD8 **ppu1_decomp_lyr_bufs,
    WORD32 *pi4_lyr_buf_stride,
    ihevce_ed_blk_t *ps_layer1_buf,
    ihevce_ed_blk_t *ps_layer2_buf,
    ihevce_ed_ctb_l1_t *ps_ed_ctb_l1,
    WORD32 i4_ol_sad_lambda_qf,
    WORD32 i4_slice_type,
    ctb_analyse_t *ps_ctb_analyse)
{
    ihevce_decomp_pre_intra_master_ctxt_t *ps_master_ctxt;
    ihevce_decomp_pre_intra_ctxt_t *ps_ctxt;
    WORD32 thread_no;

    /* Decomp state structure */
    ps_master_ctxt = (ihevce_decomp_pre_intra_master_ctxt_t *)pv_ctxt;

    for(thread_no = 0; thread_no < ps_master_ctxt->i4_num_proc_thrds; thread_no++)
    {
        WORD32 layer_no;

        ps_ctxt = ps_master_ctxt->aps_decomp_pre_intra_thrd_ctxt[thread_no];

        /* L0 layer (actual input) is registered in process call */
        for(layer_no = 1; layer_no < ps_ctxt->i4_num_layers; layer_no++)
        {
            ps_ctxt->as_layers[layer_no].i4_inp_stride = pi4_lyr_buf_stride[layer_no - 1];
            ps_ctxt->as_layers[layer_no].pu1_inp = ppu1_decomp_lyr_bufs[layer_no - 1];

            /*Populating the buffer pointers for layer1 and layer2 buffers to store the
            structure for each 4x4 block after pre intra analysis on their respective laeyrs*/

            if(layer_no == 1)
            {
                WORD32 sad_lambda_l1 = (3 * i4_ol_sad_lambda_qf >> 2);
                WORD32 temp = 1 << LAMBDA_Q_SHIFT;
                WORD32 lambda = ((temp) > sad_lambda_l1) ? temp : sad_lambda_l1;
                //ps_ctxt->as_layers[1].s_early_decision.ps_ed_pic = ps_layer1_buf;
                //ps_ctxt->as_layers[1].s_early_decision.ps_ed = ps_layer1_buf;
                ps_ctxt->ps_layer1_buf = ps_layer1_buf;
                ps_ctxt->ps_ed_ctb_l1 = ps_ed_ctb_l1;
                ps_ctxt->ai4_lambda[layer_no] = lambda;
                ps_ctxt->i4_codec_level = 0;
                ps_ctxt->i4_slice_type = i4_slice_type;
            }
            else if(layer_no == 2)
            {
                WORD32 sad_lambda_l2 = i4_ol_sad_lambda_qf >> 1;
                WORD32 temp = 1 << LAMBDA_Q_SHIFT;
                WORD32 lambda = ((temp) > sad_lambda_l2) ? temp : sad_lambda_l2;

                //ps_ctxt->as_layers[2].s_early_decision.ps_ed_pic = ps_layer2_buf;
                //ps_ctxt->as_layers[2].s_early_decision.ps_ed = ps_layer2_buf;
                ps_ctxt->ps_layer2_buf = ps_layer2_buf;
                //ihevce_ed_frame_init(ps_ctxt->ps_ed_ctxt);
                ps_ctxt->ai4_lambda[layer_no] = lambda;
                ps_ctxt->i4_codec_level = 0;
                ps_ctxt->i4_slice_type = i4_slice_type;
            }
            else
            {
                //ps_ctxt->as_layers[0].s_early_decision.ps_ed_pic = NULL;
                //ps_ctxt->as_layers[0].s_early_decision.ps_ed = NULL;
                //ps_ctxt->ps_layer1_buf = NULL;
                ps_ctxt->ai4_lambda[layer_no] = -1;
                ps_ctxt->i4_codec_level = 0;
                ps_ctxt->i4_slice_type = i4_slice_type;
            }
        }

        /* make the ps_ctb_analyse refernce as a part of the private context */
        ps_ctxt->ps_ctb_analyse = ps_ctb_analyse;
    }
}

/**
*******************************************************************************
*
* @brief
*     Merge Sort function.
*
* @par Description:
*     This function sorts the data in the input array in ascending
*     order using merge sort algorithm. Intermediate data obtained in
*     merge sort are stored in output 2-D array.
*
* @param[in]
*   pi4_input_val  :   Input 1-D array
*   aai4_output_val:   Output 2-D array containing elements sorted in sets of
*                      4,16,64 etc.
*   i4_length      : length of the array
*   i4_ip_sort_level: Input sort level. Specifies the level upto which array is sorted.
*                     It should be 1 if the array is unsorted. Should be 4 if array is sorted
*                     in sets of 4.
*   i4_op_sort_level: Output sort level. Specify the level upto which sorting is required.
*                     If it is given as length of array it sorts for whole array.
*
* @returns
*
* @remarks
*  None
*
*******************************************************************************
*/
void ihevce_merge_sort(
    WORD32 *pi4_input_val,
    WORD32 aai4_output_val[][64],
    WORD32 i4_length,
    WORD32 i4_ip_sort_level,
    WORD32 i4_op_sort_level)
{
    WORD32 i, j, k;
    WORD32 count, level;
    WORD32 temp[64];
    WORD32 *pi4_temp_buf_cpy;
    WORD32 *pi4_temp = &temp[0];
    WORD32 calc_level;

    pi4_temp_buf_cpy = pi4_temp;

    GETRANGE(calc_level, i4_op_sort_level / i4_ip_sort_level);

    calc_level = calc_level - 1;

    /*** This function is written under the assumption that we need only intermediate values of
    sort in the range of 4,16,64 etc. ***/
    ASSERT((calc_level % 2) == 0);

    /** One iteration of this for loop does 1 sets of sort and produces one intermediate value in 2 iterations **/
    for(level = 0; level < calc_level; level++)
    {
        /** Merges adjacent sets of elements based on current sort level **/
        for(count = 0; count < i4_length; (count = count + (i4_ip_sort_level * 2)))
        {
            i = 0;
            j = 0;
            if(pi4_input_val[i4_ip_sort_level - 1] < pi4_input_val[i4_ip_sort_level])
            {
                /*** Condition for early exit ***/
                memcpy(&pi4_temp[0], pi4_input_val, sizeof(WORD32) * i4_ip_sort_level * 2);
            }
            else
            {
                for(k = 0; k < (i4_ip_sort_level * 2); k++)
                {
                    if((i < i4_ip_sort_level) && (j < i4_ip_sort_level))
                    {
                        if(pi4_input_val[i] > pi4_input_val[j + i4_ip_sort_level])
                        {
                            /** copy to output array **/
                            pi4_temp[k] = pi4_input_val[j + i4_ip_sort_level];
                            j++;
                        }
                        else
                        {
                            /** copy to output array **/
                            pi4_temp[k] = pi4_input_val[i];
                            i++;
                        }
                    }
                    else if(i == i4_ip_sort_level)
                    {
                        /** copy the remaining data to output array **/
                        pi4_temp[k] = pi4_input_val[j + i4_ip_sort_level];
                        j++;
                    }
                    else
                    {
                        /** copy the remaining data to output array **/
                        pi4_temp[k] = pi4_input_val[i];
                        i++;
                    }
                }
            }
            pi4_input_val += (i4_ip_sort_level * 2);
            pi4_temp += (i4_ip_sort_level * 2);
        }
        pi4_input_val = pi4_temp - i4_length;

        if(level % 2)
        {
            /** Assign a temp address for storing next sort level output as we will not need this data as output **/
            pi4_temp = pi4_temp_buf_cpy;
        }
        else
        {
            /** Assign address for storing the intermediate data into output 2-D array **/
            pi4_temp = aai4_output_val[level / 2];
        }
        i4_ip_sort_level *= 2;
    }
}

void ihevce_decomp_pre_intra_curr_frame_pre_intra_deinit(
    void *pv_pre_intra_ctxt,
    pre_enc_me_ctxt_t *ps_curr_out,
    WORD32 i4_is_last_thread,
    frm_ctb_ctxt_t *ps_frm_ctb_prms,
    WORD32 i4_temporal_lyr_id,
    WORD32 i4_enable_noise_detection)
{
    ihevce_decomp_pre_intra_master_ctxt_t *ps_pre_intra_master_ctxt =
        (ihevce_decomp_pre_intra_master_ctxt_t *)pv_pre_intra_ctxt;
    ihevce_decomp_pre_intra_ctxt_t *ps_pre_intra_ctxt =
        ps_pre_intra_master_ctxt->aps_decomp_pre_intra_thrd_ctxt[0];

    WORD32 i4_k;
    WORD32 ctb_ctr, vert_ctr;

    WORD32 ai4_curr_frame_8x8_sum_act[2] = { 0, 0 };
    LWORD64 ai8_curr_frame_8x8_sum_act_sqr[2] = { 0, 0 };
    WORD32 ai4_curr_frame_8x8_sum_blks[2] = { 0, 0 };
    ULWORD64 u8_curr_frame_8x8_sum_act_sqr = 0;

    LWORD64 ai8_curr_frame_16x16_sum_act_sqr[3] = { 0, 0, 0 };
    WORD32 ai4_curr_frame_16x16_sum_act[3] = { 0, 0, 0 };
    WORD32 ai4_curr_frame_16x16_sum_blks[3] = { 0, 0, 0 };

    LWORD64 ai8_curr_frame_32x32_sum_act_sqr[3] = { 0, 0, 0 };
    WORD32 ai4_curr_frame_32x32_sum_act[3] = { 0, 0, 0 };
    WORD32 ai4_curr_frame_32x32_sum_blks[3] = { 0, 0, 0 };

    (void)i4_temporal_lyr_id;
    (void)i4_enable_noise_detection;

    if(i4_is_last_thread == 1)
    {
        WORD32 i4_slice_type = ps_curr_out->s_slice_hdr.i1_slice_type;
        //ps_pre_intra_ctxt->i4_slice_type;
        WORD32 ctb_ctr_blks = ps_pre_intra_ctxt->as_layers[1].i4_num_col_blks;
        WORD32 vert_ctr_blks = ps_pre_intra_ctxt->as_layers[1].i4_num_row_blks;
        ihevce_ed_ctb_l1_t *ps_ed_ctb_pic_l1 = ps_curr_out->ps_ed_ctb_l1;
        WORD32 block_wd = ps_pre_intra_ctxt->as_layers[1].i4_decomp_blk_wd;
        WORD32 inc_ctb = ((block_wd >> 2) * (block_wd >> 2));
        ihevce_ed_blk_t *ps_ed_blk_l1 = ps_curr_out->ps_layer1_buf;
        ihevce_ed_blk_t *ps_ed;
        WORD32 i, j;
        WORD32 i4_avg_noise_satd;
        WORD32 k;
        WORD32 i4_layer_wd = ps_pre_intra_ctxt->as_layers[1].i4_actual_wd;
        WORD32 i4_layer_ht = ps_pre_intra_ctxt->as_layers[1].i4_actual_ht;

        /*Calculate min noise threshold */
        /*Min noise threshold is calculted by taking average of lowest 1% satd val in the complete 4x4 frame satds*/
        //ihevce_ed_ctxt_t *ps_ed_ctxt =  ps_pre_intra_ctxt->ps_ed_ctxt;
        WORD32 i4_min_blk = ((MIN_BLKS * (i4_layer_wd >> 1) * (i4_layer_ht >> 1)) / 100);
        WORD32 ai4_noise_thr_hstrgm[MAX_SATD_THRSHLD];
        memset(&ai4_noise_thr_hstrgm[0], 0, (sizeof(WORD32) * MAX_SATD_THRSHLD));
        ASSERT(!(USE_CUR_L0_SATD && USE_CUR_SATD));
        for(vert_ctr = 0; vert_ctr < vert_ctr_blks; vert_ctr++)
        {
            ps_ed = ps_ed_blk_l1 + (vert_ctr * inc_ctb * (ctb_ctr_blks));
            for(ctb_ctr = 0; ctb_ctr < ctb_ctr_blks; ctb_ctr++)
            {
                /* Populate avg satd to calculate MI and activity factors */
                for(i = 0; i < 4; i++)
                {
                    for(j = 0; j < 4; j++)
                    {
                        for(k = 0; k < 4; k++)
                        {
                            if(-1 != (ps_ed + j * 4 + i * 16 + k)->i4_4x4_satd)
                            {
                                WORD32 i4_satd_lim;
                                i4_satd_lim = (ps_ed + j * 4 + i * 16 + k)->i4_4x4_satd;
                                /* Histogram creation for Noise threshold */
                                if(i4_satd_lim < MAX_SATD_THRSHLD)
                                {
                                    ai4_noise_thr_hstrgm[i4_satd_lim]++;
                                }
                            }
                        }
                    }
                }
                ps_ed += inc_ctb;
            }
        }
        {
            WORD32 i4_total_blks = 0;
            LWORD64 i8_acc_satd = 0;
            for(i = MIN_SATD_THRSHLD; i < MAX_SATD_THRSHLD; i++)
            {
                i4_total_blks += ai4_noise_thr_hstrgm[i];
                i8_acc_satd += (i * ai4_noise_thr_hstrgm[i]);

                if(i4_total_blks > i4_min_blk)
                    break;
            }
            if(i4_total_blks < i4_min_blk)
            {
                i4_avg_noise_satd = SATD_NOISE_FLOOR_THRESHOLD;
            }
            else
            {
                i4_avg_noise_satd = (WORD32)(i8_acc_satd + (i4_total_blks >> 1)) / i4_total_blks;
            }
        }

        ps_curr_out->i4_avg_noise_thrshld_4x4 = i4_avg_noise_satd;

        for(vert_ctr = 0; vert_ctr < vert_ctr_blks; vert_ctr++)
        {
            ihevce_ed_ctb_l1_t *ps_ed_ctb_row_l1 =
                ps_ed_ctb_pic_l1 + vert_ctr * ps_frm_ctb_prms->i4_num_ctbs_horz;
            ps_ed = ps_ed_blk_l1 + (vert_ctr * inc_ctb * (ctb_ctr_blks));

            for(ctb_ctr = 0; ctb_ctr < ctb_ctr_blks; ctb_ctr++)
            {
                /*sum of (sum of L1_4x4 @ L1_8x8) @ L1_16x16 level */
                WORD32 ai4_sum_sum_4x4_satd_16x16[4] = { 0, 0, 0, 0 };
                /*min of (sum of L1_4x4 @ L1_8x8) @ L1_16x16 level */
                WORD32 ai4_min_sum_4x4_satd_16x16[4] = {
                    MAX_32BIT_VAL, MAX_32BIT_VAL, MAX_32BIT_VAL, MAX_32BIT_VAL
                };
                /*min of (min of L1_4x4 @ L1_8x8) @ L1_16x16 level */
                WORD32 ai4_min_min_4x4_satd_16x16[4] = {
                    MAX_32BIT_VAL, MAX_32BIT_VAL, MAX_32BIT_VAL, MAX_32BIT_VAL
                };
                WORD32 i4_sum_4x4_satd, i4_min_4x4_satd;
                ihevce_ed_ctb_l1_t *ps_ed_ctb_curr_l1 = ps_ed_ctb_row_l1 + ctb_ctr;

                WORD32 is_min_block_uncompensated_in_l32x32 = 0;

                /*min of L1_4x4 @ L1_8x8*/
                WORD32 ai4_min_satd_ctb[MAX_CTB_SIZE];
                /*** This 2-D array will contain 4x4 satds sorted in ascending order in sets of 4,16,64 ***/
                /*** For example : '5 10 2 7 6 12 3 1' array input will return '2 5 7 10 1 3 6 12' if sorted in sets of 4 ***/
                WORD32 aai4_min_4_16_64_satd[3][MAX_CTB_SIZE];

                /*sum of L1_4x4 @ L1_8x8*/
                WORD32 ai4_sum_satd_ctb[MAX_CTB_SIZE >> 2];
                /*** This 2-D array will contain 4x4 satds sorted in ascending order in sets of 4,16***/
                WORD32 aai4_sum_4_16_satd_ctb[2][MAX_CTB_SIZE];

                /* sum of (sum of L1_4x4 @ L1_8x8) @ L1_16x16 */
                WORD32 ai4_sum_sum_satd_ctb[(MAX_CTB_SIZE >> 2) >> 2];
                /*L1_32x32 = L0_64x64
                so in L1_32x32 there are 64 L1_4x4blocks*/
                for(i = 0; i < MAX_CTB_SIZE; i++)
                {
                    ai4_min_satd_ctb[i] = -1;
                }
                for(j = 0; j < 3; j++)
                {
                    for(i = 0; i < MAX_CTB_SIZE; i++)
                    {
                        aai4_min_4_16_64_satd[j][i] = -1;
                    }
                }
                /*L1_32x32 = L0_64x64
                so in L1_32x32 there are 16 L1_8x8blocks*/
                for(i = 0; i < (MAX_CTB_SIZE >> 2); i++)
                {
                    ai4_sum_satd_ctb[i] = -1;
                }
                for(j = 0; j < 2; j++)
                {
                    for(i = 0; i < (MAX_CTB_SIZE >> 2); i++)
                    {
                        aai4_sum_4_16_satd_ctb[j][i] = -1;
                    }
                }
                /*L1_32x32 = L0_64x64
                so in L1_32x32 there are 16 L1_16x16blocks*/
                for(i = 0; i < ((MAX_CTB_SIZE >> 2) >> 2); i++)
                {
                    ai4_sum_sum_satd_ctb[i] = 0;
                }
                /*Populate sum min 4x4 activty */
                /*loop for L1_32x32 block*/
                for(i = 0; i < 4; i++)
                {
                    /*loop for L1_16x16 block*/
                    for(j = 0; j < 4; j++)
                    {
                        WORD32 i4_sum_satd_dumyy = 0;
                        WORD32 i4_num_satd_blks = 0;
                        /* loop for L1_8x8 block*/
                        for(k = 0; k < 4; k++)
                        {
                            WORD32 i4_satd_lim;
                            i4_satd_lim = (ps_ed + j * 4 + i * 16 + k)->i4_4x4_satd;

                            /*complete ctb will not have i4_4x4_satd = -1*/
                            if(-1 != i4_satd_lim)
                            {
#if SUB_NOISE_THRSHLD
                                i4_satd_lim = i4_satd_lim - i4_avg_noise_satd;
                                if(i4_satd_lim < 0)
                                {
                                    i4_satd_lim = 0;
                                }
#else
                                if(i4_satd_lim < i4_avg_noise_satd)
                                {
                                    i4_satd_lim = i4_avg_noise_satd;
                                }
#endif
                                i4_num_satd_blks++;
                                /*populate 4x4 data to calculate modulation index */
                                (ps_ed + j * 4 + i * 16 + k)->i4_4x4_satd = i4_satd_lim;

                                i4_sum_satd_dumyy += i4_satd_lim;
                                ai4_min_satd_ctb[j * 4 + i * 16 + k] = i4_satd_lim;
                            }
                        }
                        if(i4_num_satd_blks != 0)
                        {
                            /*make the sum of satd always for 4 blocks even it is incomplete ctb */
                            i4_sum_satd_dumyy = i4_sum_satd_dumyy * 4 / i4_num_satd_blks;
                        }
                        else
                        {
                            i4_sum_satd_dumyy = -1;
                        }
                        /*sum of L1_4x4 @ L1_8x8block level*/
                        ai4_sum_satd_ctb[j + i * 4] = i4_sum_satd_dumyy;
                        /*sum of L1_8x8 @ L1_16x16block level*/
                        ai4_sum_sum_satd_ctb[i] += i4_sum_satd_dumyy;
                        /*store sum of 4x4 @ L1_8x8block level*/
                        ps_ed_ctb_curr_l1->i4_sum_4x4_satd[i * 4 + j] = i4_sum_satd_dumyy;
                        /*store min of 4x4 @ L1_8x8block level */
                        //ps_ed_ctb_curr_l1->i4_min_4x4_satd[i * 4 + j] = i4_min_satd_dumyy;
                    }
                }
                {
                    WORD32 i4_array_length = sizeof(ai4_min_satd_ctb) / sizeof(WORD32);

                    /*** This function will sort 64 elements in array ai4_min_satd_ctb in ascending order to ***/
                    /*** 3 arrays in sets of 4,16,64 into the 2-D array   aai4_min_4_16_64_satd              ***/
                    ihevce_merge_sort(
                        &ai4_min_satd_ctb[0], aai4_min_4_16_64_satd, i4_array_length, 1, 64);

                    i4_array_length = sizeof(ai4_sum_satd_ctb) / sizeof(WORD32);

                    /*** This function will sort 16 elements in array ai4_sum_satd_ctb in ascending order to ***/
                    /*** 2 arrays in sets of 4,16 into the 2-D array   aai4_sum_4_16_satd_ctb                ***/
                    ihevce_merge_sort(
                        &ai4_sum_satd_ctb[0], aai4_sum_4_16_satd_ctb, i4_array_length, 1, 16);
                }

                /*Populate avg satd to calculate MI and activity factors*/
                for(i = 0; i < 4; i++)
                {
                    WORD32 is_min_block_uncompensated_in_l116x16 = 0;
                    ps_ed_ctb_curr_l1->i4_16x16_satd[i][0] = -1;
                    ps_ed_ctb_curr_l1->i4_16x16_satd[i][1] = -1;
                    ps_ed_ctb_curr_l1->i4_16x16_satd[i][2] = -1;

                    for(j = 0; j < 4; j++)
                    {
                        ps_ed_ctb_curr_l1->i4_min_4x4_satd[i * 4 + j] =
                            aai4_min_4_16_64_satd[0][i * 16 + j * 4 + MEDIAN_CU_TU];
                        /*Accumulate the sum of 8*8 activities in the current layer (16*16 CU in L0)*/
                        i4_sum_4x4_satd = ps_ed_ctb_curr_l1->i4_sum_4x4_satd[i * 4 + j];
                        i4_min_4x4_satd = ps_ed_ctb_curr_l1->i4_min_4x4_satd[i * 4 + j];
                        ps_ed_ctb_curr_l1->i4_8x8_satd[i * 4 + j][0] = -1;
                        ps_ed_ctb_curr_l1->i4_8x8_satd[i * 4 + j][1] = -1;
                        ASSERT(-2 != i4_sum_4x4_satd);

                        if((-1 != i4_sum_4x4_satd))
                        {
                            WORD32 not_skipped = 1;

                            if((i4_slice_type == ISLICE) || (1 == not_skipped))
                            {
                                is_min_block_uncompensated_in_l116x16 = 1;
                                is_min_block_uncompensated_in_l32x32 = 1;

                                u8_curr_frame_8x8_sum_act_sqr +=
                                    (i4_sum_4x4_satd * i4_sum_4x4_satd);

                                ai4_curr_frame_8x8_sum_act[0] += i4_sum_4x4_satd;
                                ai8_curr_frame_8x8_sum_act_sqr[0] +=
                                    (i4_sum_4x4_satd * i4_sum_4x4_satd);
                                ai4_curr_frame_8x8_sum_blks[0] += 1;
                                ai4_curr_frame_8x8_sum_act[1] += i4_min_4x4_satd;
                                ai8_curr_frame_8x8_sum_act_sqr[1] +=
                                    (i4_min_4x4_satd * i4_min_4x4_satd);
                                ai4_curr_frame_8x8_sum_blks[1] += 1;
                            }

                            ps_ed_ctb_curr_l1->i4_8x8_satd[i * 4 + j][0] = i4_sum_4x4_satd;
                            ps_ed_ctb_curr_l1->i4_8x8_satd[i * 4 + j][1] = i4_min_4x4_satd;
                        }
                        else
                        {
                            ai4_sum_sum_4x4_satd_16x16[i] = MAX_32BIT_VAL;
                            ai4_min_sum_4x4_satd_16x16[i] = MAX_32BIT_VAL;
                            ai4_min_min_4x4_satd_16x16[i] = MAX_32BIT_VAL;
                        }
                    }

                    //if(1 == is_min_block_comensated_in_l116x16)
                    {
                        ai4_min_sum_4x4_satd_16x16[i] =
                            aai4_sum_4_16_satd_ctb[0][i * 4 + MEDIAN_CU_TU];
                        ai4_min_min_4x4_satd_16x16[i] =
                            aai4_min_4_16_64_satd[1][i * 16 + MEDIAN_CU_TU_BY_2];

                        if(ai4_sum_sum_4x4_satd_16x16[i] != MAX_32BIT_VAL)
                        {
                            ai4_sum_sum_4x4_satd_16x16[i] = 0;
                            for(j = 0; j < 4; j++)
                            {
                                ai4_sum_sum_4x4_satd_16x16[i] +=
                                    ps_ed_ctb_curr_l1->i4_sum_4x4_satd[i * 4 + j];
                            }
                            ps_ed_ctb_curr_l1->i4_16x16_satd[i][0] = ai4_sum_sum_4x4_satd_16x16[i];
                            ps_ed_ctb_curr_l1->i4_16x16_satd[i][1] = ai4_min_sum_4x4_satd_16x16[i];
                            ps_ed_ctb_curr_l1->i4_16x16_satd[i][2] = ai4_min_min_4x4_satd_16x16[i];
                        }
                    }
                    if(1 == is_min_block_uncompensated_in_l116x16)
                    {
                        if(MAX_32BIT_VAL != ai4_sum_sum_4x4_satd_16x16[i])
                        {
                            ai4_curr_frame_16x16_sum_act[0] += ai4_sum_sum_4x4_satd_16x16[i];
                            ai8_curr_frame_16x16_sum_act_sqr[0] +=
                                (ai4_sum_sum_4x4_satd_16x16[i] * ai4_sum_sum_4x4_satd_16x16[i]);
                            ai4_curr_frame_16x16_sum_blks[0] += 1;
                        }
                        if(MAX_32BIT_VAL != ai4_min_sum_4x4_satd_16x16[i])
                        {
                            ai4_curr_frame_16x16_sum_act[1] += ai4_min_sum_4x4_satd_16x16[i];
                            ai8_curr_frame_16x16_sum_act_sqr[1] +=
                                (ai4_min_sum_4x4_satd_16x16[i] * ai4_min_sum_4x4_satd_16x16[i]);
                            ai4_curr_frame_16x16_sum_blks[1] += 1;
                            ai4_curr_frame_16x16_sum_act[2] += ai4_min_min_4x4_satd_16x16[i];
                            ai8_curr_frame_16x16_sum_act_sqr[2] +=
                                (ai4_min_min_4x4_satd_16x16[i] * ai4_min_min_4x4_satd_16x16[i]);
                            ai4_curr_frame_16x16_sum_blks[2] += 1;
                        }
                    }
                }
                /*32x32*/
                {
                    ps_ed_ctb_curr_l1->i4_32x32_satd[0][0] = -1;
                    ps_ed_ctb_curr_l1->i4_32x32_satd[0][1] = -1;
                    ps_ed_ctb_curr_l1->i4_32x32_satd[0][2] = -1;
                    ps_ed_ctb_curr_l1->i4_32x32_satd[0][3] = -1;

                    if((MAX_32BIT_VAL != ai4_sum_sum_4x4_satd_16x16[0]) ||
                       (MAX_32BIT_VAL != ai4_sum_sum_4x4_satd_16x16[2]) ||
                       (MAX_32BIT_VAL != ai4_sum_sum_4x4_satd_16x16[1]) ||
                       (MAX_32BIT_VAL != ai4_sum_sum_4x4_satd_16x16[3]))
                    {
                        //if(1 == is_min_block_comensated_in_l32x32)
                        {
                            {
                                WORD32 aai4_min_sum_sum_4x4_satd_16x16[1][64];
                                WORD32 i4_array_length =
                                    sizeof(ai4_sum_sum_4x4_satd_16x16) / sizeof(WORD32);
                                /*** Sort 4 elements in ascending order ***/
                                ihevce_merge_sort(
                                    &ai4_sum_sum_4x4_satd_16x16[0],
                                    aai4_min_sum_sum_4x4_satd_16x16,
                                    i4_array_length,
                                    1,
                                    4);

                                ps_ed_ctb_curr_l1->i4_32x32_satd[0][0] =
                                    aai4_min_sum_sum_4x4_satd_16x16[0][MEDIAN_CU_TU];
                            }
                            {
                                ps_ed_ctb_curr_l1->i4_32x32_satd[0][1] =
                                    aai4_sum_4_16_satd_ctb[1][MEDIAN_CU_TU_BY_2];
                            }
                            {
                                ps_ed_ctb_curr_l1->i4_32x32_satd[0][2] =
                                    aai4_min_4_16_64_satd[2][MEDIAN_CU_TU_BY_4];
                            }

                            /*Sum of all 32x32 activity */
                            ps_ed_ctb_curr_l1->i4_32x32_satd[0][3] = 0;
                            for(j = 0; j < 4; j++)
                            {
                                if(MAX_32BIT_VAL != ai4_sum_sum_4x4_satd_16x16[j])
                                    ps_ed_ctb_curr_l1->i4_32x32_satd[0][3] +=
                                        ai4_sum_sum_4x4_satd_16x16[j];
                            }

                            if(1 == is_min_block_uncompensated_in_l32x32)
                            {
                                /*Accumulate the sum of 32*32 activities in the current layer (64*64 CU in L0)*/
                                if(MAX_32BIT_VAL != ps_ed_ctb_curr_l1->i4_32x32_satd[0][0])
                                {
                                    ai4_curr_frame_32x32_sum_act[0] +=
                                        ps_ed_ctb_curr_l1->i4_32x32_satd[0][0];
                                    ai8_curr_frame_32x32_sum_act_sqr[0] +=
                                        (ps_ed_ctb_curr_l1->i4_32x32_satd[0][0] *
                                         ps_ed_ctb_curr_l1->i4_32x32_satd[0][0]);
                                    ai4_curr_frame_32x32_sum_blks[0] += 1;
                                }

                                if(MAX_32BIT_VAL != ps_ed_ctb_curr_l1->i4_32x32_satd[0][1])
                                {
                                    ai4_curr_frame_32x32_sum_act[1] +=
                                        ps_ed_ctb_curr_l1->i4_32x32_satd[0][1];
                                    ai8_curr_frame_32x32_sum_act_sqr[1] +=
                                        (ps_ed_ctb_curr_l1->i4_32x32_satd[0][1] *
                                         ps_ed_ctb_curr_l1->i4_32x32_satd[0][1]);
                                    ai4_curr_frame_32x32_sum_blks[1] += 1;
                                }

                                if(MAX_32BIT_VAL != ps_ed_ctb_curr_l1->i4_32x32_satd[0][2])
                                {
                                    ai4_curr_frame_32x32_sum_act[2] +=
                                        ps_ed_ctb_curr_l1->i4_32x32_satd[0][2];
                                    ai8_curr_frame_32x32_sum_act_sqr[2] +=
                                        (ps_ed_ctb_curr_l1->i4_32x32_satd[0][2] *
                                         ps_ed_ctb_curr_l1->i4_32x32_satd[0][2]);
                                    ai4_curr_frame_32x32_sum_blks[2] += 1;
                                }
                            }
                        }
                    }
                }
                /*Increment ctb count*/
                ps_ed += inc_ctb;
            }
        }

        /* Spatial Variation and modulation index calculated for the frame */
        {
            for(i4_k = 0; i4_k < 2; i4_k++)
            {
                /*8x8*/
#if USE_SQRT_AVG_OF_SATD_SQR
                ps_curr_out->i8_curr_frame_8x8_sum_act[i4_k] = ai8_curr_frame_8x8_sum_act_sqr[i4_k];
#else
                ps_curr_out->i8_curr_frame_8x8_sum_act[i4_k] = ai4_curr_frame_8x8_sum_act[i4_k];
#endif
                ps_curr_out->i4_curr_frame_8x8_sum_act_for_strength[i4_k] =
                    ai4_curr_frame_8x8_sum_act[i4_k];
                ps_curr_out->i4_curr_frame_8x8_num_blks[i4_k] = ai4_curr_frame_8x8_sum_blks[i4_k];
                ps_curr_out->u8_curr_frame_8x8_sum_act_sqr = u8_curr_frame_8x8_sum_act_sqr;

                /*16x16*/
#if USE_SQRT_AVG_OF_SATD_SQR
                ps_curr_out->i8_curr_frame_16x16_sum_act[i4_k] =
                    ai8_curr_frame_16x16_sum_act_sqr[i4_k];
#else
                ps_curr_out->i8_curr_frame_16x16_sum_act[i4_k] = ai4_curr_frame_16x16_sum_act[i4_k];
#endif
                ps_curr_out->i4_curr_frame_16x16_num_blks[i4_k] =
                    ai4_curr_frame_16x16_sum_blks[i4_k];

                /*32x32*/
#if USE_SQRT_AVG_OF_SATD_SQR
                ps_curr_out->i8_curr_frame_32x32_sum_act[i4_k] =
                    ai8_curr_frame_32x32_sum_act_sqr[i4_k];
#else
                ps_curr_out->i8_curr_frame_32x32_sum_act[i4_k] = ai4_curr_frame_32x32_sum_act[i4_k];
#endif
                ps_curr_out->i4_curr_frame_32x32_num_blks[i4_k] =
                    ai4_curr_frame_32x32_sum_blks[i4_k];
            }

            /*16x16*/
#if USE_SQRT_AVG_OF_SATD_SQR
            ps_curr_out->i8_curr_frame_16x16_sum_act[2] = ai8_curr_frame_16x16_sum_act_sqr[2];
#else
            ps_curr_out->i8_curr_frame_16x16_sum_act[2] = ai4_curr_frame_16x16_sum_act[2];
#endif

            ps_curr_out->i4_curr_frame_16x16_num_blks[2] = ai4_curr_frame_16x16_sum_blks[2];

            /*32x32*/
#if USE_SQRT_AVG_OF_SATD_SQR
            ps_curr_out->i8_curr_frame_32x32_sum_act[2] = ai8_curr_frame_32x32_sum_act_sqr[2];
#else
            ps_curr_out->i8_curr_frame_32x32_sum_act[2] = ai4_curr_frame_32x32_sum_act[2];
#endif
            ps_curr_out->i4_curr_frame_32x32_num_blks[2] = ai4_curr_frame_32x32_sum_blks[2];
        }
    }
}

/*!
******************************************************************************
* \if Function name : ihevce_decomp_pre_intra_get_frame_satd \endif
*
* \brief
*    Number of memory records are returned for enc_loop module
*
*
* \return
*    None
*
* \author
*  Ittiam
*
*****************************************************************************
*/
LWORD64 ihevce_decomp_pre_intra_get_frame_satd(void *pv_ctxt, WORD32 *i4_width, WORD32 *i4_hieght)
{
    ihevce_decomp_pre_intra_master_ctxt_t *ps_master_ctxt =
        (ihevce_decomp_pre_intra_master_ctxt_t *)pv_ctxt;
    WORD32 i4_i;
    LWORD64 i8_tot_satd = 0;

    /*accumulate SATD acorss all thread. note that every thread will enter this function,
    hence it must be guranteed that all thread must have completed preintra pass by now*/
    for(i4_i = 0; i4_i < ps_master_ctxt->i4_num_proc_thrds; i4_i++)
    {
        ihevce_decomp_pre_intra_ctxt_t *ps_ctxt =
            ps_master_ctxt->aps_decomp_pre_intra_thrd_ctxt[i4_i];

        //i8_tot_satd += ps_ctxt->as_layers[1].s_early_decision.i8_sum_best_satd;
        i8_tot_satd += ps_ctxt->ps_ed_ctxt->i8_sum_best_satd;

        *i4_width = ps_ctxt->as_layers[1].i4_actual_wd;
        *i4_hieght = ps_ctxt->as_layers[1].i4_actual_ht;
    }

    return i8_tot_satd;
}

LWORD64 ihevce_decomp_pre_intra_get_frame_satd_squared(
    void *pv_ctxt, WORD32 *i4_width, WORD32 *i4_hieght)
{
    ihevce_decomp_pre_intra_master_ctxt_t *ps_master_ctxt =
        (ihevce_decomp_pre_intra_master_ctxt_t *)pv_ctxt;
    WORD32 i4_i;
    LWORD64 i8_tot_satd = 0;

    /*accumulate SATD acorss all thread. note that every thread will enter this function,
    hence it must be guranteed that all thread must have completed preintra pass by now*/
    for(i4_i = 0; i4_i < ps_master_ctxt->i4_num_proc_thrds; i4_i++)
    {
        ihevce_decomp_pre_intra_ctxt_t *ps_ctxt =
            ps_master_ctxt->aps_decomp_pre_intra_thrd_ctxt[i4_i];

        //i8_tot_satd += ps_ctxt->as_layers[1].s_early_decision.i8_sum_best_satd;
        i8_tot_satd += (ps_ctxt->ps_ed_ctxt->i8_sum_sq_best_satd);

        *i4_width = ps_ctxt->as_layers[1].i4_actual_wd;
        *i4_hieght = ps_ctxt->as_layers[1].i4_actual_ht;
    }

    return i8_tot_satd;
}
