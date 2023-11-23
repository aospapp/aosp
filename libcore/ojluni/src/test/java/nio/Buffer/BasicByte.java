/*
 * Copyright (c) 2000, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/* Type-specific source code for unit test
 *
 * Regenerate the BasicX classes via genBasic.sh whenever this file changes.
 * We check in the generated source files so that the test tree can be used
 * independently of the rest of the source tree.
 */
package test.java.nio.Buffer;

// -- This file was mechanically generated: Do not edit! -- //


import java.io.IOException;
import java.io.UncheckedIOException;

import java.nio.*;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;



public class BasicByte
    extends Basic
{

    private static final byte[] VALUES = {
        Byte.MIN_VALUE,
        (byte) -1,
        (byte) 0,
        (byte) 1,
        Byte.MAX_VALUE,












    };

    private static void relGet(ByteBuffer b) {
        int n = b.capacity();
        for (int i = 0; i < n; i++)
            ck(b, (long)b.get(), (long)((byte)ic(i)));
        b.rewind();
    }

    private static void relGet(ByteBuffer b, int start) {
        int n = b.remaining();
        for (int i = start; i < n; i++)
            ck(b, (long)b.get(), (long)((byte)ic(i)));
        b.rewind();
    }

    private static void absGet(ByteBuffer b) {
        int n = b.capacity();
        for (int i = 0; i < n; i++)
            ck(b, (long)b.get(), (long)((byte)ic(i)));
        b.rewind();
    }

    private static void bulkGet(ByteBuffer b) {
        int n = b.capacity();
        byte[] a = new byte[n + 7];
        b.get(a, 7, n);
        for (int i = 0; i < n; i++) {
            ck(b, (long)a[i + 7], (long)((byte)ic(i)));
        }
    }

    private static void relPut(ByteBuffer b) {
        int n = b.capacity();
        b.clear();
        for (int i = 0; i < n; i++)
            b.put((byte)ic(i));
        b.flip();
    }

    private static void absPut(ByteBuffer b) {
        int n = b.capacity();
        b.clear();
        for (int i = 0; i < n; i++)
            b.put(i, (byte)ic(i));
        b.limit(n);
        b.position(0);
    }

    private static void bulkPutArray(ByteBuffer b) {
        int n = b.capacity();
        b.clear();
        byte[] a = new byte[n + 7];
        for (int i = 0; i < n; i++)
            a[i + 7] = (byte)ic(i);
        b.put(a, 7, n);
        b.flip();
    }

    private static void bulkPutBuffer(ByteBuffer b) {
        int n = b.capacity();
        b.clear();
        ByteBuffer c = ByteBuffer.allocate(n + 7);
        c.position(7);
        for (int i = 0; i < n; i++)
            c.put((byte)ic(i));
        c.flip();
        c.position(7);
        b.put(c);
        b.flip();
        try {
            b.put(b);
            fail("IllegalArgumentException expected for put into same buffer");
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null) {
                fail("Non-null IllegalArgumentException message expected from"
                     + " put into same buffer");
            }
        }
    }

    //6231529
    private static void callReset(ByteBuffer b) {
        b.position(0);
        b.mark();

        b.duplicate().reset();
        b.asReadOnlyBuffer().reset();
    }









































    private static void checkSlice(ByteBuffer b, ByteBuffer slice) {
        ck(slice, 0, slice.position());
        ck(slice, b.remaining(), slice.limit());
        ck(slice, b.remaining(), slice.capacity());
        if (b.isDirect() != slice.isDirect())
            fail("Lost direction", slice);
        if (b.isReadOnly() != slice.isReadOnly())
            fail("Lost read-only", slice);
    }



    private static void checkBytes(ByteBuffer b, byte[] bs) {
        int n = bs.length;
        int p = b.position();
        if (b.order() == ByteOrder.BIG_ENDIAN) {
            for (int i = 0; i < n; i++) {
                ck(b, b.get(), bs[i]);
            }
        } else {
            for (int i = n - 1; i >= 0; i--) {
                ck(b, b.get(), bs[i]);
            }
        }
        b.position(p);
    }

    private static void compact(Buffer b) {
        try {
            Class<?> cl = b.getClass();
            java.lang.reflect.Method m = cl.getDeclaredMethod("compact");
            m.setAccessible(true);
            m.invoke(b);
        } catch (Exception e) {
            fail(e.getMessage(), b);
        }
    }

    private static void checkInvalidMarkException(final Buffer b) {
        tryCatch(b, InvalidMarkException.class, () -> {
                b.mark();
                compact(b);
                b.reset();
            });
    }

    private static void testHet(int level, ByteBuffer b) {

        int p = b.position();
        b.limit(b.capacity());

        b.putChar((char)1);
        b.putChar((char)Character.MAX_VALUE);

        b.putShort((short)1);
        b.putShort((short)Short.MAX_VALUE);

        b.putInt(1);
        b.putInt(Integer.MAX_VALUE);

        b.putLong((long)1);
        b.putLong((long)Long.MAX_VALUE);

        b.putFloat((float)1);
        b.putFloat((float)Float.MIN_VALUE);
        b.putFloat((float)Float.MAX_VALUE);

        b.putDouble((double)1);
        b.putDouble((double)Double.MIN_VALUE);
        b.putDouble((double)Double.MAX_VALUE);

        b.limit(b.position());
        b.position(p);

        ck(b, b.getChar(), 1);
        ck(b, b.getChar(), Character.MAX_VALUE);

        ck(b, b.getShort(), 1);
        ck(b, b.getShort(), Short.MAX_VALUE);

        ck(b, b.getInt(), 1);
        ck(b, b.getInt(), Integer.MAX_VALUE);

        ck(b, b.getLong(), 1);
        ck(b, b.getLong(), Long.MAX_VALUE);

        ck(b, (long)b.getFloat(), 1);
        ck(b, (long)b.getFloat(), (long)Float.MIN_VALUE);
        ck(b, (long)b.getFloat(), (long)Float.MAX_VALUE);

        ck(b, (long)b.getDouble(), 1);
        ck(b, (long)b.getDouble(), (long)Double.MIN_VALUE);
        ck(b, (long)b.getDouble(), (long)Double.MAX_VALUE);

    }

    private static void testAlign(final ByteBuffer b, boolean direct) {
        // index out-of bounds
        catchIllegalArgument(b, () -> b.alignmentOffset(-1, (short) 1));

        // unit size values
        catchIllegalArgument(b, () -> b.alignmentOffset(0, (short) 0));
        for (int us = 1; us < 65; us++) {
            int _us = us;
            if ((us & (us - 1)) != 0) {
                // unit size not a power of two
                catchIllegalArgument(b, () -> b.alignmentOffset(0, _us));
            } else {
                if (direct || us <= 8) {
                    b.alignmentOffset(0, us);
                } else {
                    // unit size > 8 with non-direct buffer
                    tryCatch(b, UnsupportedOperationException.class,
                            () -> b.alignmentOffset(0, _us));
                }
            }
        }

        // Probe for long misalignment at index zero for a newly created buffer
        ByteBuffer empty =
                direct ? ByteBuffer.allocateDirect(0) : ByteBuffer.allocate(0);
        int longMisalignmentAtZero = empty.alignmentOffset(0, 8);

        if (direct) {
            // Freshly created direct byte buffers should be aligned at index 0
            // for ref and primitive values (see Unsafe.allocateMemory)
            if (longMisalignmentAtZero != 0) {
                fail("Direct byte buffer misaligned at index 0"
                        + " for ref and primitive values "
                        + longMisalignmentAtZero);
            }
        } else {
            // For heap byte buffers misalignment may occur on 32-bit systems
            // where Unsafe.ARRAY_BYTE_BASE_OFFSET % 8 == 4 and not 0
            // Note the GC will preserve alignment of the base address of the
            // array
            if (jdk.internal.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET % 8
                    != longMisalignmentAtZero) {
                fail("Heap byte buffer misaligned at index 0"
                        + " for ref and primitive values "
                        + longMisalignmentAtZero);
            }
        }

        // Ensure test buffer is correctly aligned at index 0
        if (b.alignmentOffset(0, 8) != longMisalignmentAtZero)
            fail("Test input buffer not correctly aligned at index 0", b);

        // Test misalignment values
        for (int us : new int[]{1, 2, 4, 8}) {
            for (int i = 0; i < us * 2; i++) {
                int am = b.alignmentOffset(i, us);
                int expectedAm = (longMisalignmentAtZero + i) % us;

                if (am != expectedAm) {
                    String f = "b.alignmentOffset(%d, %d) == %d incorrect, expected %d";
                    fail(String.format(f, i, us, am, expectedAm));
                }
            }
        }

        // Created aligned slice to test against
        int ap = 8 - longMisalignmentAtZero;
        int al = b.limit() - b.alignmentOffset(b.limit(), 8);
        ByteBuffer intermediate = (ByteBuffer) b.position(ap).limit(al);
        ByteBuffer ab = intermediate.slice();
        if (ab.limit() == 0) {
            fail("Test input buffer not sufficiently sized to cover" +
                    " an aligned region for all values", b);
        }
        if (ab.alignmentOffset(0, 8) != 0)
            fail("Aligned test input buffer not correctly aligned at index 0", ab);

        for (int us : new int[]{1, 2, 4, 8}) {
            for (int p = 1; p < 16; p++) {
                int l = ab.limit() - p;

                intermediate = (ByteBuffer) ab.slice().position(p).limit(l);
                ByteBuffer as = intermediate.alignedSlice(us);

                ck(as, 0, as.position());
                ck(as, as.capacity(), as.limit());
                if (b.isDirect() != as.isDirect())
                    fail("Lost direction", as);
                if (b.isReadOnly() != as.isReadOnly())
                    fail("Lost read-only", as);

                int p_mod = ab.alignmentOffset(p, us);
                int l_mod = ab.alignmentOffset(l, us);
                // Round up position
                p = (p_mod > 0) ? p + (us - p_mod) : p;
                // Round down limit
                l = l - l_mod;

                int ec = l - p;
                if (as.limit() != ec) {
                    fail("Buffer capacity incorrect, expected: " + ec, as);
                }
            }
        }

        // mapped buffers
        try {
            for (MappedByteBuffer bb : mappedBuffers()) {
                try {
                    int offset = bb.alignmentOffset(1, 4);
                    ck(bb, offset >= 0);
                } catch (UnsupportedOperationException e) {
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // alignment identities
        final int maxPow2 = 12;
        ByteBuffer bb = ByteBuffer.allocateDirect(1 << maxPow2); // cap 4096

        Random rnd = new Random();
        long seed = rnd.nextLong();
        rnd = new Random(seed);

        for (int i = 0; i < 100; i++) {
            // 1 == 2^0 <= unitSize == 2^k <= bb.capacity()/2
            int unitSize = 1 << rnd.nextInt(maxPow2);
            // 0 <= index < 2*unitSize
            int index = rnd.nextInt(unitSize << 1);
            int value = bb.alignmentOffset(index, unitSize);
            try {
                if (value < 0 || value >= unitSize) {
                    throw new RuntimeException(value + " < 0 || " +
                        value + " >= " + unitSize);
                }
                if (value <= index &&
                    bb.alignmentOffset(index - value, unitSize) != 0)
                    throw new RuntimeException("Identity 1");
                if (bb.alignmentOffset(index + (unitSize - value),
                    unitSize) != 0)
                    throw new RuntimeException("Identity 2");
            } catch (RuntimeException re) {
                System.err.format("seed %d, index %d, unitSize %d, value %d%n",
                    seed, index, unitSize, value);
                throw re;
            }
        }
    }

    private static MappedByteBuffer[] mappedBuffers() throws IOException {
        return new MappedByteBuffer[]{
                createMappedBuffer(new byte[]{0, 1, 2, 3}),
                createMappedBuffer(new byte[]{0, 1, 2, -3,
                    45, 6, 7, 78, 3, -7, 6, 7, -128, 127}),
        };
    }

    private static MappedByteBuffer createMappedBuffer(byte[] contents)
        throws IOException {
        Path tempFile = Files.createTempFile("mbb", null);
        tempFile.toFile().deleteOnExit();
        Files.write(tempFile, contents);
        try (FileChannel fc = FileChannel.open(tempFile)) {
            MappedByteBuffer map =
                fc.map(FileChannel.MapMode.READ_ONLY, 0, contents.length);
            map.load();
            return map;
        }
    }


    private static void fail(String problem,
                             ByteBuffer xb, ByteBuffer yb,
                             byte x, byte y) {
        fail(problem + String.format(": x=%s y=%s", x, y), xb, yb);
    }

    private static void catchNullArgument(Buffer b, Runnable thunk) {
        tryCatch(b, NullPointerException.class, thunk);
    }

    private static void catchIllegalArgument(Buffer b, Runnable thunk) {
        tryCatch(b, IllegalArgumentException.class, thunk);
    }

    private static void catchReadOnlyBuffer(Buffer b, Runnable thunk) {
        tryCatch(b, ReadOnlyBufferException.class, thunk);
    }

    private static void catchIndexOutOfBounds(Buffer b, Runnable thunk) {
        tryCatch(b, IndexOutOfBoundsException.class, thunk);
    }

    private static void catchIndexOutOfBounds(byte[] t, Runnable thunk) {
        tryCatch(t, IndexOutOfBoundsException.class, thunk);
    }

    private static void tryCatch(Buffer b, Class<?> ex, Runnable thunk) {
        boolean caught = false;
        try {
            thunk.run();
        } catch (Throwable x) {
            if (ex.isAssignableFrom(x.getClass())) {
                caught = true;
            } else {
                String s = x.getMessage();
                if (s == null)
                    s = x.getClass().getName();
                fail(s + " not expected");
            }
        }
        if (!caught) {
            fail(ex.getName() + " not thrown", b);
        }
    }

    private static void tryCatch(byte[] t, Class<?> ex, Runnable thunk) {
        tryCatch(ByteBuffer.wrap(t), ex, thunk);
    }

    public static void test(int level, final ByteBuffer b, boolean direct) {

        if (direct != b.isDirect())
            fail("Wrong direction", b);

        // Gets and puts

        relPut(b);
        relGet(b);
        absGet(b);
        bulkGet(b);

        absPut(b);
        relGet(b);
        absGet(b);
        bulkGet(b);

        bulkPutArray(b);
        relGet(b);

        bulkPutBuffer(b);
        relGet(b);





































        // Compact

        relPut(b);
        b.position(13);
        b.compact();
        b.flip();
        relGet(b, 13);

        // Exceptions

        relPut(b);
        b.limit(b.capacity() / 2);
        b.position(b.limit());

        tryCatch(b, BufferUnderflowException.class, () -> b.get());
        tryCatch(b, BufferOverflowException.class, () -> b.put((byte)42));
        // The index must be non-negative and less than the buffer's limit.
        catchIndexOutOfBounds(b, () -> b.get(b.limit()));
        catchIndexOutOfBounds(b, () -> b.get(-1));
        catchIndexOutOfBounds(b, () -> b.put(b.limit(), (byte)42));
        ByteBuffer intermediate = (ByteBuffer) b.position(0).mark();
        tryCatch(b, InvalidMarkException.class,
                () -> intermediate.compact().reset());

        try {
            b.position(b.limit() + 1);
            fail("IllegalArgumentException expected for position beyond limit");
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null) {
                fail("Non-null IllegalArgumentException message expected for"
                     + " position beyond limit");
            }
        }

        try {
            b.position(-1);
            fail("IllegalArgumentException expected for negative position");
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null) {
                fail("Non-null IllegalArgumentException message expected for"
                     + " negative position");
            }
        }

        try {
            b.limit(b.capacity() + 1);
            fail("IllegalArgumentException expected for limit beyond capacity");
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null) {
                fail("Non-null IllegalArgumentException message expected for"
                     + " limit beyond capacity");
            }
        }

        try {
            b.limit(-1);
            fail("IllegalArgumentException expected for negative limit");
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null) {
                fail("Non-null IllegalArgumentException message expected for"
                     + " negative limit");
            }
        }

        // Exceptions in absolute bulk and slice operations

        catchIndexOutOfBounds(b, () -> b.slice(-1, 7));
        catchIndexOutOfBounds(b, () -> b.slice(b.limit() + 1, 7));
        catchIndexOutOfBounds(b, () -> b.slice(0, -1));
        catchIndexOutOfBounds(b, () -> b.slice(7, b.limit() - 7 + 1));

        // Values

        b.clear();
        b.put((byte)0);
        b.put((byte)-1);
        b.put((byte)1);
        b.put(Byte.MAX_VALUE);
        b.put(Byte.MIN_VALUE);

















        b.flip();
        ck(b, b.get(), 0);
        ck(b, b.get(), (byte)-1);
        ck(b, b.get(), 1);
        ck(b, b.get(), Byte.MAX_VALUE);
        ck(b, b.get(), Byte.MIN_VALUE);



























        // Comparison
        b.rewind();
        ByteBuffer b2 = ByteBuffer.allocate(b.capacity());
        b2.put(b);
        b2.flip();
        b.position(2);
        b2.position(2);
        if (!b.equals(b2)) {
            for (int i = 2; i < b.limit(); i++) {
                byte x = b.get(i);
                byte y = b2.get(i);
                if (x != y






                    ) {
                }
            }
            fail("Identical buffers not equal", b, b2);
        }
        if (b.compareTo(b2) != 0) {
            fail("Comparison to identical buffer != 0", b, b2);
        }
        b.limit(b.limit() + 1);
        b.position(b.limit() - 1);
        b.put((byte)99);
        b.rewind();
        b2.rewind();
        if (b.equals(b2))
            fail("Non-identical buffers equal", b, b2);
        if (b.compareTo(b2) <= 0)
            fail("Comparison to shorter buffer <= 0", b, b2);
        b.limit(b.limit() - 1);

        b.put(2, (byte)42);
        if (b.equals(b2))
            fail("Non-identical buffers equal", b, b2);
        if (b.compareTo(b2) <= 0)
            fail("Comparison to lesser buffer <= 0", b, b2);

        // Check equals and compareTo with interesting values
        for (byte x : VALUES) {
            ByteBuffer xb = ByteBuffer.wrap(new byte[] { x });
            for (byte y : VALUES) {
                ByteBuffer yb = ByteBuffer.wrap(new byte[] { y });
                if (xb.compareTo(yb) != - yb.compareTo(xb)) {
                    fail("compareTo not anti-symmetric",
                         xb, yb, x, y);
                }
                if ((xb.compareTo(yb) == 0) != xb.equals(yb)) {
                    fail("compareTo inconsistent with equals",
                         xb, yb, x, y);
                }
                if (xb.compareTo(yb) != Byte.compare(x, y)) {






                    fail("Incorrect results for ByteBuffer.compareTo",
                         xb, yb, x, y);
                }









                if (xb.equals(yb) != (x == y)) {
                    fail("Incorrect results for ByteBuffer.equals",
                         xb, yb, x, y);
                }







            }
        }

        // Sub, dup

        relPut(b);
        relGet(b.duplicate());
        b.position(13);
        relGet(b.duplicate(), 13);
        relGet(b.duplicate().slice(), 13);
        relGet(b.slice(), 13);
        relGet(b.slice().duplicate(), 13);

        // Slice

        b.position(5);
        ByteBuffer sb = b.slice();
        checkSlice(b, sb);
        b.position(0);
        ByteBuffer sb2 = sb.slice();
        checkSlice(sb, sb2);

        if (!sb.equals(sb2))
            fail("Sliced slices do not match", sb, sb2);
        if ((sb.hasArray()) && (sb.arrayOffset() != sb2.arrayOffset())) {
            fail("Array offsets do not match: "
                 + sb.arrayOffset() + " != " + sb2.arrayOffset(), sb, sb2);
        }

        int bPos = b.position();
        int bLim = b.limit();

        b.position(7);
        b.limit(42);
        ByteBuffer rsb = b.slice();
        b.position(0);
        b.limit(b.capacity());
        ByteBuffer asb = b.slice(7, 35);
        checkSlice(rsb, asb);

        b.position(bPos);
        b.limit(bLim);





        // Heterogeneous accessors

        b.order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i <= 9; i++) {
            b.position(i);
            testHet(level + 1, b);
        }
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.position(3);
        testHet(level + 1, b);

        // Test alignment

        testAlign(b, direct);

    }











































    public static void test(final byte [] ba) {
        int offset = 47;
        int length = 900;
        final ByteBuffer b = ByteBuffer.wrap(ba, offset, length);
        ck(b, b.capacity(), ba.length);
        ck(b, b.position(), offset);
        ck(b, b.limit(), offset + length);

        // The offset must be non-negative and no larger than <array.length>.
        catchIndexOutOfBounds(ba, () -> ByteBuffer.wrap(ba, -1, ba.length));
        catchIndexOutOfBounds(ba, () -> ByteBuffer.wrap(ba, ba.length + 1, ba.length));
        catchIndexOutOfBounds(ba, () -> ByteBuffer.wrap(ba, 0, -1));
        catchIndexOutOfBounds(ba, () -> ByteBuffer.wrap(ba, 0, ba.length + 1));

        // A NullPointerException will be thrown if the array is null.
        tryCatch(ba, NullPointerException.class,
                () -> ByteBuffer.wrap((byte []) null, 0, 5));
        tryCatch(ba, NullPointerException.class,
                () -> ByteBuffer.wrap((byte []) null));
    }

    private static void testAllocate() {
        // An IllegalArgumentException will be thrown for negative capacities.
        catchIllegalArgument((Buffer) null, () -> ByteBuffer.allocate(-1));
        try {
            ByteBuffer.allocate(-1);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null) {
                fail("Non-null IllegalArgumentException message expected for"
                     + " attempt to allocate negative capacity buffer");
            }
        }

        catchIllegalArgument((Buffer) null, () -> ByteBuffer.allocateDirect(-1));
        try {
            ByteBuffer.allocateDirect(-1);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null) {
                fail("Non-null IllegalArgumentException message expected for"
                     + " attempt to allocate negative capacity direct buffer");
            }
        }

    }

    public static void test() {
        testAllocate();
        test(0, ByteBuffer.allocate(7 * 1024), false);
        test(0, ByteBuffer.wrap(new byte[7 * 1024], 0, 7 * 1024), false);
        test(new byte[1024]);

        ByteBuffer b = ByteBuffer.allocateDirect(7 * 1024);
        for (b.position(0); b.position() < b.limit(); )
            ck(b, b.get(), 0);
        test(0, b, true);





        callReset(ByteBuffer.allocate(10));





    }

}
