/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define ENTRY(f) .text; .align 4; .globl f; .type f,#function; f:
#define END(f) .size f, .-f;

/* Perform the actual YuvToRGB conversion in a macro, from register to
 * register.  This macro will be called from within several different wrapper
 * variants for different data layouts.  Y data starts with the even and odd
 * bytes split into the low parts of v8 and v9 respectively.  U and V are in
 * v16 and v17.  Working constants are pre-loaded into v13-v15, and v3 is
 * pre-loaded with a constant 0xff alpha channel.
 *
 * The complicated arithmetic is the result of refactoring the original
 * equations to avoid 16-bit overflow without losing any precision.
 */
.macro yuvkern
        movi        v7.8b, #149

        umull       v1.8h, v8.8b, v7.8b        // g0 = y0 * 149
        umull       v5.8h, v9.8b, v7.8b        // g1 = y1 * 149

        movi        v7.8b, #50
        movi        v10.8b, #104
        umull       v8.8h, v16.8b, v7.8b       // g2 = u * 50 + v * 104
        umlal       v8.8h, v17.8b, v10.8b

        ushr        v7.8b, v17.8b, #1
        uaddw       v0.8h, v1.8h, v7.8b        // r0 = y0 * 149 + (v >> 1)
        uaddw       v4.8h, v5.8h, v7.8b        // r1 = y1 * 149 + (v >> 1)

        ushll       v7.8h, v16.8b, #2
        add         v2.8h, v1.8h, v7.8h        // b0 = y0 * 149 + (u << 2)
        add         v6.8h, v5.8h, v7.8h        // b1 = y1 * 149 + (u << 2)

        movi        v7.16b, #204
        movi        v10.8b, #254
        umull       v11.8h, v17.8b, v7.8b     // r2 = v * 204
        umull       v12.8h, v16.8b, v10.8b      // b2 = u * 254

        uhadd       v0.8h, v0.8h, v11.8h       // r0 = (r0 + r2) >> 1
        uhadd       v4.8h, v4.8h, v11.8h       // r1 = (r1 + r2) >> 1
        uqadd       v1.8h, v1.8h, v14.8h       // g0 = satu16(g0 + (-16 * 149 + 128 * 50 + 128 * 104) >> 0)
        uqadd       v5.8h, v5.8h, v14.8h       // g1 = satu16(g1 + (-16 * 149 + 128 * 50 + 128 * 104) >> 0)
        uhadd       v2.8h, v2.8h, v12.8h       // b0 = (b0 + b2) >> 1
        uhadd       v6.8h, v6.8h, v12.8h       // b1 = (b1 + b2) >> 1

        uqsub       v0.8h, v0.8h, v13.8h       // r0 = satu16(r0 - (16 * 149 + (128 >> 1) + 128 * 204) >> 1)
        uqsub       v4.8h, v4.8h, v13.8h       // r1 = satu16(r1 - (16 * 149 + (128 >> 1) + 128 * 204) >> 1)
        uqsub       v1.8h, v1.8h, v8.8h        // g0 = satu16(g0 - g2)
        uqsub       v5.8h, v5.8h, v8.8h        // g1 = satu16(g1 - g2)
        uqsub       v2.8h, v2.8h, v15.8h       // b0 = satu16(b0 - (16 * 149 + (128 << 2) + 128 * 254) >> 1)
        uqsub       v6.8h, v6.8h, v15.8h       // b1 = satu16(b1 - (16 * 149 + (128 << 2) + 128 * 254) >> 1)

        uqrshrn     v0.8b, v0.8h, #6
        uqrshrn     v4.8b, v4.8h, #6
        uqrshrn     v1.8b, v1.8h, #7
        uqrshrn     v5.8b, v5.8h, #7
        uqrshrn     v2.8b, v2.8h, #6
        uqrshrn     v6.8b, v6.8h, #6

        zip1        v0.16b, v0.16b, v4.16b
        zip1        v1.16b, v1.16b, v5.16b
        zip1        v2.16b, v2.16b, v6.16b
.endm

/* Define the wrapper code which will load and store the data, iterate the
 * correct number of times, and safely handle the remainder at the end of the
 * loop.  Some sections of code are switched out depending on the data packing
 * being handled.
 */
.macro wrap_line kernel, interleaved=0, swapuv=0

        mov         w5, #((16 * 149 + (128 >> 1) + 128 * 204) >> 1)
        dup         v13.8h, w5
        mov         w5, #((-16 * 149 + 128 * 50 + 128 * 104) >> 0)
        dup         v14.8h, w5
        mov         w5, #((16 * 149 + (128 << 2) + 128 * 254) >> 1)
        dup         v15.8h, w5

        movi        v3.16b, #0xff

        subs        x2, x2, #16
        bhs         1f
        b           2f

        .align 4
1:      ld2         {v8.8b,v9.8b}, [x1], #16
//      prfm PLDL1STRM, [x1, #256]
  .if \interleaved
    .if \swapuv
        ld2         {v17.8b,v18.8b}, [x3], #16
        mov         v16.8b, v18.8b
    .else
        ld2         {v16.8b,v17.8b}, [x3], #16
    .endif
//      prfm PLD1STRM,  [x3, #256]
  .else
        ld1         {v16.8b}, [x3], #8
        ld1         {v17.8b}, [x4], #8
//      prfm PLD1STRM,  [x3, #128]
//      prfm PLD1STRM,  [x4, #128]
  .endif

        \kernel

        subs        x2, x2, #16

        st4         {v0.16b,v1.16b,v2.16b,v3.16b}, [x0], #64

        bhs         1b

2:      adds        x2, x2, #16
        beq         2f

        /* To handle the tail portion of the data (something less than 16
         * bytes) load small power-of-two chunks into working registers.  It
         * doesn't matter where they end up in the register; the same process
         * will store them back out using the same positions and the
         * interaction between neighbouring pixels is constrained to odd
         * boundaries where the load operations don't interfere.
         */
        movi        v8.8b, #0
        movi        v9.8b, #0
        movi        v16.8b, #0
        movi        v17.8b, #0

        tbz         x2, #3, 1f
        ld1         {v9.8b}, [x1], #8
  .if \interleaved
        ld1         {v17.8b}, [x3], #8
  .else
        ld1         {v16.s}[1], [x3], #4
        ld1         {v17.s}[1], [x4], #4
  .endif
1:      tbz         x2, #2, 1f
        ld1         {v8.s}[1], [x1], #4
  .if \interleaved
        ld1         {v16.s}[1], [x3], #4
  .else
        ld1         {v16.h}[1], [x3], #2
        ld1         {v17.h}[1], [x4], #2
  .endif
1:      tbz         x2, #1, 1f
        ld1         {v8.h}[1], [x1], #2
  .if \interleaved
        ld1         {v16.h}[1], [x3], #2
  .else
        ld1         {v16.b}[1], [x3], #1
        ld1         {v17.b}[1], [x4], #1
  .endif
1:      tbz         x2, #0, 1f
        ld1         {v8.b}[1], [x1], #1
  .if \interleaved
        ld1         {v16.h}[0], [x3], #2
  .else
        ld1         {v16.b}[0], [x3], #1
        ld1         {v17.b}[0], [x4], #1
  .endif

        /* One small impediment in the process above is that some of the load
         * operations can't perform byte-wise structure deinterleaving at the
         * same time as loading only part of a register.  So the data is loaded
         * linearly and unpacked manually at this point if necessary.
         */
1:      uzp1        v8.16b, v8.16b, v9.16b
  .if \interleaved
    .if \swapuv
        uzp1        v16.16b, v17.16b, v16.16b
    .else
        uzp1        v16.16b, v16.16b, v17.16b
    .endif
  .endif

        \kernel

        /* As above but with the output; structured stores for partial vectors
         * aren't available, so the data is re-packed first and stored linearly.
         */
        zip1        v4.16b, v0.16b, v2.16b
        zip2        v6.16b, v0.16b, v2.16b
        zip1        v5.16b, v1.16b, v3.16b
        zip2        v7.16b, v1.16b, v3.16b
        zip1        v0.16b, v4.16b, v5.16b
        zip2        v1.16b, v4.16b, v5.16b
        zip1        v2.16b, v6.16b, v7.16b
        zip2        v3.16b, v6.16b, v7.16b

1:      tbz         x2, #3, 1f
        st1         {v2.16b,v3.16b}, [x0], #32
1:      tbz         x2, #2, 1f
        st1         {v1.16b}, [x0], #16
1:      tbz         x2, #1, 1f
        st1         {v0.d}[1], [x0], #8
1:      tbz         x2, #0, 2f
        st1         {v0.s}[1], [x0], #4
2:
.endm


/*  void rsdIntrinsicYuv2_K(
 *          void *out,          // x0
 *          void const *yin,    // x1
 *          void const *uin,    // x2
 *          void const *vin,    // x3
 *          size_t xstart,      // x4
 *          size_t xend);       // x5
 */
ENTRY(rsdIntrinsicYuv2_K)
        lsr         x6, x4, #1
        add         x0, x0, x4, LSL #2
        add         x1, x1, x4
        add         x4, x3, x6
        add         x3, x2, x6
        sub         x2, x5, x6, LSL #2

        sub         x6, sp, #32
        sub         sp, sp, #64
        st1         {v8.1d - v11.1d}, [sp]
        st1         {v12.1d - v15.1d}, [x6]

        wrap_line yuvkern, 0

        ld1         {v8.1d - v11.1d}, [sp], #32
        ld1         {v12.1d - v15.1d}, [sp], #32
        ret
END(rsdIntrinsicYuv2_K)

/*  void rsdIntrinsicYuv_K(
 *          void *out,          // x0
 *          void const *yin,    // x1
 *          void const *uvin,   // x2
 *          size_t xstart,      // x3
 *          size_t xend);       // x4
 */
ENTRY(rsdIntrinsicYuv_K)
        bic         x5, x3, #1
        add         x0, x0, x5, LSL #2
        add         x1, x1, x5
        add         x3, x2, x5
        sub         x2, x4, x5

        sub         x5, sp, #32
        sub         sp, sp, #64
        st1         {v8.1d - v11.1d}, [sp]
        st1         {v12.1d - v15.1d}, [x5]

        wrap_line yuvkern, 1, 1

        ld1         {v8.1d - v11.1d}, [sp], #32
        ld1         {v12.1d - v15.1d}, [sp], #32
        ret
END(rsdIntrinsicYuv_K)

/*  void rsdIntrinsicYuvR_K(
 *          void *out,          // x0
 *          void const *yin,    // x1
 *          void const *uvin,   // x2
 *          size_t xstart,      // x3
 *          size_t xend);       // x4
 */
ENTRY(rsdIntrinsicYuvR_K)
        bic         x5, x3, #1
        add         x0, x0, x5, LSL #2
        add         x1, x1, x5
        add         x3, x2, x5
        sub         x2, x4, x5

        sub         x5, sp, #32
        sub         sp, sp, #64
        st1         {v8.1d - v11.1d}, [sp]
        st1         {v12.1d - v15.1d}, [x5]

        wrap_line yuvkern, 1

        ld1         {v8.1d - v11.1d}, [sp], #32
        ld1         {v12.1d - v15.1d}, [sp], #32
        ret
END(rsdIntrinsicYuvR_K)
