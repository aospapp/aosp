/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.binder.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import test_package.Bar;
import test_package.Foo;
import test_package.IEmpty;
import test_package.ITest;
import test_package.RegularPolygon;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class JavaClientTest {
    private final String TAG = "JavaClientTest";

    private Class mServiceClass;
    private ITest mInterface;
    private String mExpectedName;
    private boolean mShouldBeRemote;

    public JavaClientTest(Class serviceClass, String expectedName, boolean shouldBeRemote) {
        mServiceClass = serviceClass;
        mExpectedName = expectedName;
        mShouldBeRemote = shouldBeRemote;
    }

    @Parameterized.Parameters( name = "{0}" )
    public static Collection<Object[]> data() {
        // For local interfaces, this test will parcel the data locally.
        // Whenever possible, the desired service should be accessed directly
        // in order to avoid this additional overhead.
        return Arrays.asList(new Object[][] {
                {NativeService.Local.class, "CPP", false /*shouldBeRemote*/},
                {JavaService.Local.class, "JAVA", false /*shouldBeRemote*/},
                {NativeService.Remote.class, "CPP", true /*shouldBeRemote*/},
                {JavaService.Remote.class, "JAVA", true /*shouldBeRemote*/},
            });
    }

    @Before
    public void setUp() {
        Log.e(TAG, "Setting up");

        SyncTestServiceConnection connection = new SyncTestServiceConnection(
            InstrumentationRegistry.getTargetContext(), mServiceClass);

        mInterface = connection.get();
        assertNotEquals(null, mInterface);
    }

    @Test
    public void testSanityCheckSource() throws RemoteException {
        String name = mInterface.GetName();

        Log.i(TAG, "Service GetName: " + name);
        assertEquals(mExpectedName, name);
    }

    @Test
    public void testTrivial() throws RemoteException {
        mInterface.TestVoidReturn();
        mInterface.TestOneway();
    }

    private void checkDump(String expected, String[] args) throws RemoteException, IOException {
        ParcelFileDescriptor[] sockets = ParcelFileDescriptor.createReliableSocketPair();
        ParcelFileDescriptor socketIn = sockets[0];
        ParcelFileDescriptor socketOut = sockets[1];

        mInterface.asBinder().dump(socketIn.getFileDescriptor(), args);
        socketIn.close();

        FileInputStream fileInputStream = new ParcelFileDescriptor.AutoCloseInputStream(socketOut);

        byte[] expectedBytes = expected.getBytes();
        byte[] input = new byte[expectedBytes.length];

        assertEquals(input.length, fileInputStream.read(input));
        Assert.assertArrayEquals(input, expectedBytes);
    }

    @Test
    public void testDump() throws RemoteException, IOException {
        checkDump("", new String[]{});
        checkDump("", new String[]{"", ""});
        checkDump("Hello World!", new String[]{"Hello ", "World!"});
        checkDump("ABC", new String[]{"A", "B", "C"});
    }

    @Test
    public void testCallingInfo() throws RemoteException {
      mInterface.CacheCallingInfoFromOneway();

      assertEquals(Process.myPid(), mInterface.GiveMeMyCallingPid());
      assertEquals(Process.myUid(), mInterface.GiveMeMyCallingUid());

      if (mShouldBeRemote) {
        // PID is hidden from oneway calls
        assertEquals(0, mInterface.GiveMeMyCallingPidFromOneway());
      } else {
        assertEquals(Process.myPid(), mInterface.GiveMeMyCallingPidFromOneway());
      }

      assertEquals(Process.myUid(), mInterface.GiveMeMyCallingUidFromOneway());
    }

    @Test
    public void testRepeatPrimitives() throws RemoteException {
        assertEquals(1, mInterface.RepeatInt(1));
        assertEquals(2, mInterface.RepeatLong(2));
        assertEquals(1.0f, mInterface.RepeatFloat(1.0f), 0.0f);
        assertEquals(2.0, mInterface.RepeatDouble(2.0), 0.0);
        assertEquals(true, mInterface.RepeatBoolean(true));
        assertEquals('a', mInterface.RepeatChar('a'));
        assertEquals((byte)3, mInterface.RepeatByte((byte)3));
    }

    @Test
    public void testRepeatBinder() throws RemoteException {
        IBinder binder = mInterface.asBinder();

        assertEquals(binder, mInterface.RepeatBinder(binder));
        assertEquals(binder, mInterface.RepeatNullableBinder(binder));
        assertEquals(null, mInterface.RepeatNullableBinder(null));
    }

    private static class Empty extends IEmpty.Stub {
        @Override
        public int getInterfaceVersion() { return Empty.VERSION; }
    }

    @Test
    public void testRepeatInterface() throws RemoteException {
        IEmpty empty = new Empty();

        assertEquals(empty, mInterface.RepeatInterface(empty));
        assertEquals(empty, mInterface.RepeatNullableInterface(empty));
        assertEquals(null, mInterface.RepeatNullableInterface(null));
    }

    private static interface IRepeatFd {
        ParcelFileDescriptor repeat(ParcelFileDescriptor fd) throws RemoteException;
    }

    private void checkFdRepeated(IRepeatFd transformer) throws RemoteException, IOException {
        ParcelFileDescriptor[] sockets = ParcelFileDescriptor.createReliableSocketPair();
        ParcelFileDescriptor socketIn = sockets[0];
        ParcelFileDescriptor socketOut = sockets[1];

        ParcelFileDescriptor repeatFd = transformer.repeat(socketIn);

        boolean isNativeRemote = mInterface.GetName().equals("CPP");
        try {
            socketOut.checkError();

            // Either native didn't properly call detach, or native properly handles detach, and
            // we should change the test to enforce that socket comms work.
            assertFalse("Native doesn't implement comm fd but did not get detach.", isNativeRemote);
        } catch (ParcelFileDescriptor.FileDescriptorDetachedException e) {
            assertTrue("Detach, so remote should be native", isNativeRemote);
        }

        // Both backends support these.
        socketIn.checkError();
        repeatFd.checkError();

        FileOutputStream repeatFdStream = new ParcelFileDescriptor.AutoCloseOutputStream(repeatFd);
        String testData = "asdf";
        byte[] output = testData.getBytes();
        repeatFdStream.write(output);
        repeatFdStream.close();

        FileInputStream fileInputStream = new ParcelFileDescriptor.AutoCloseInputStream(socketOut);
        byte[] input = new byte[output.length];

        assertEquals(input.length, fileInputStream.read(input));
        Assert.assertArrayEquals(input, output);
    }

    @Test
    public void testRepeatFd() throws RemoteException, IOException {
        checkFdRepeated((fd) -> mInterface.RepeatFd(fd));
    }

    @Test
    public void testRepeatNullableFd() throws RemoteException, IOException {
        checkFdRepeated((fd) -> mInterface.RepeatNullableFd(fd));
        assertEquals(null, mInterface.RepeatNullableFd(null));
    }

    @Test
    public void testRepeatString() throws RemoteException {
        assertEquals("", mInterface.RepeatString(""));
        assertEquals("a", mInterface.RepeatString("a"));
        assertEquals("foo", mInterface.RepeatString("foo"));
    }

    @Test
    public void testRepeatNullableString() throws RemoteException {
        assertEquals(null, mInterface.RepeatNullableString(null));
        assertEquals("", mInterface.RepeatNullableString(""));
        assertEquals("a", mInterface.RepeatNullableString("a"));
        assertEquals("foo", mInterface.RepeatNullableString("foo"));
    }

    public void assertPolygonEquals(RegularPolygon lhs, RegularPolygon rhs) {
        assertEquals(lhs.name, rhs.name);
        assertEquals(lhs.numSides, rhs.numSides);
        assertEquals(lhs.sideLength, rhs.sideLength, 0.0f);
    }
    public void assertPolygonEquals(RegularPolygon[] lhs, RegularPolygon[] rhs) {
        assertEquals(lhs.length, rhs.length);
        for (int i = 0; i < lhs.length; i++) {
            assertPolygonEquals(lhs[i], rhs[i]);
        }
    }

    @Test
    public void testRepeatPolygon() throws RemoteException {
        RegularPolygon polygon = new RegularPolygon();
        polygon.name = "hexagon";
        polygon.numSides = 6;
        polygon.sideLength = 1.0f;

        RegularPolygon result = mInterface.RepeatPolygon(polygon);

        assertPolygonEquals(polygon, result);
    }

    @Test
    public void testInsAndOuts() throws RemoteException {
        RegularPolygon polygon = new RegularPolygon();
        mInterface.RenamePolygon(polygon, "Jerry");
        assertEquals("Jerry", polygon.name);
    }

    @Test
    public void testArrays() throws RemoteException {
        {
            boolean[] value = {};
            boolean[] out1 = new boolean[value.length];
            boolean[] out2 = mInterface.RepeatBooleanArray(value, out1);

            Assert.assertArrayEquals(value, out1);
            Assert.assertArrayEquals(value, out2);
        }
        {
            boolean[] value = {false, true, false};
            boolean[] out1 = new boolean[value.length];
            boolean[] out2 = mInterface.RepeatBooleanArray(value, out1);

            Assert.assertArrayEquals(value, out1);
            Assert.assertArrayEquals(value, out2);
        }
        {
            byte[] value = {1, 2, 3};
            byte[] out1 = new byte[value.length];
            byte[] out2 = mInterface.RepeatByteArray(value, out1);

            Assert.assertArrayEquals(value, out1);
            Assert.assertArrayEquals(value, out2);
        }
        {
            char[] value = {'h', 'a', '!'};
            char[] out1 = new char[value.length];
            char[] out2 = mInterface.RepeatCharArray(value, out1);

            Assert.assertArrayEquals(value, out1);
            Assert.assertArrayEquals(value, out2);
        }
        {
            int[] value = {1, 2, 3};
            int[] out1 = new int[value.length];
            int[] out2 = mInterface.RepeatIntArray(value, out1);

            Assert.assertArrayEquals(value, out1);
            Assert.assertArrayEquals(value, out2);
        }
        {
            long[] value = {1, 2, 3};
            long[] out1 = new long[value.length];
            long[] out2 = mInterface.RepeatLongArray(value, out1);

            Assert.assertArrayEquals(value, out1);
            Assert.assertArrayEquals(value, out2);
        }
        {
            float[] value = {1.0f, 2.0f, 3.0f};
            float[] out1 = new float[value.length];
            float[] out2 = mInterface.RepeatFloatArray(value, out1);

            Assert.assertArrayEquals(value, out1, 0.0f);
            Assert.assertArrayEquals(value, out2, 0.0f);
        }
        {
            double[] value = {1.0, 2.0, 3.0};
            double[] out1 = new double[value.length];
            double[] out2 = mInterface.RepeatDoubleArray(value, out1);

            Assert.assertArrayEquals(value, out1, 0.0);
            Assert.assertArrayEquals(value, out2, 0.0);
        }
        {
            String[] value = {"", "aoeu", "lol", "brb"};
            String[] out1 = new String[value.length];
            String[] out2 = mInterface.RepeatStringArray(value, out1);

            Assert.assertArrayEquals(value, out1);
            Assert.assertArrayEquals(value, out2);
        }
        {

            RegularPolygon septagon = new RegularPolygon();
            septagon.name = "septagon";
            septagon.numSides = 7;
            septagon.sideLength = 1.0f;

            RegularPolygon[] value = {septagon, new RegularPolygon(), new RegularPolygon()};
            RegularPolygon[] out1 = new RegularPolygon[value.length];
            RegularPolygon[] out2 = mInterface.RepeatRegularPolygonArray(value, out1);

            assertPolygonEquals(value, out1);
            assertPolygonEquals(value, out2);
        }
    }

    @Test
    public void testNullableArrays() throws RemoteException {
        {
            boolean[] emptyValue = {};
            boolean[] value = {false, true, false};
            Assert.assertArrayEquals(null, mInterface.RepeatNullableBooleanArray(null));
            Assert.assertArrayEquals(emptyValue, mInterface.RepeatNullableBooleanArray(emptyValue));
            Assert.assertArrayEquals(value, mInterface.RepeatNullableBooleanArray(value));
        }
        {
            byte[] emptyValue = {};
            byte[] value = {1, 2, 3};
            Assert.assertArrayEquals(null, mInterface.RepeatNullableByteArray(null));
            Assert.assertArrayEquals(emptyValue, mInterface.RepeatNullableByteArray(emptyValue));
            Assert.assertArrayEquals(value, mInterface.RepeatNullableByteArray(value));
        }
        {
            char[] emptyValue = {};
            char[] value = {'h', 'a', '!'};
            Assert.assertArrayEquals(null, mInterface.RepeatNullableCharArray(null));
            Assert.assertArrayEquals(emptyValue, mInterface.RepeatNullableCharArray(emptyValue));
            Assert.assertArrayEquals(value, mInterface.RepeatNullableCharArray(value));
        }
        {
            int[] emptyValue = {};
            int[] value = {1, 2, 3};
            Assert.assertArrayEquals(null, mInterface.RepeatNullableIntArray(null));
            Assert.assertArrayEquals(emptyValue, mInterface.RepeatNullableIntArray(emptyValue));
            Assert.assertArrayEquals(value, mInterface.RepeatNullableIntArray(value));
        }
        {
            long[] emptyValue = {};
            long[] value = {1, 2, 3};
            Assert.assertArrayEquals(null, mInterface.RepeatNullableLongArray(null));
            Assert.assertArrayEquals(emptyValue, mInterface.RepeatNullableLongArray(emptyValue));
            Assert.assertArrayEquals(value, mInterface.RepeatNullableLongArray(value));
        }
        {
            float[] emptyValue = {};
            float[] value = {1.0f, 2.0f, 3.0f};
            Assert.assertArrayEquals(null, mInterface.RepeatNullableFloatArray(null), 0.0f);
            Assert.assertArrayEquals(emptyValue, mInterface.RepeatNullableFloatArray(emptyValue), 0.0f);
            Assert.assertArrayEquals(value, mInterface.RepeatNullableFloatArray(value), 0.0f);
        }
        {
            double[] emptyValue = {};
            double[] value = {1.0, 2.0, 3.0};
            Assert.assertArrayEquals(null, mInterface.RepeatNullableDoubleArray(null), 0.0);
            Assert.assertArrayEquals(emptyValue, mInterface.RepeatNullableDoubleArray(emptyValue), 0.0);
            Assert.assertArrayEquals(value, mInterface.RepeatNullableDoubleArray(value), 0.0);
        }
        {
            String[] emptyValue = {};
            String[] value = {"", "aoeu", null, "brb"};
            Assert.assertArrayEquals(null, mInterface.RepeatNullableStringArray(null));
            Assert.assertArrayEquals(emptyValue, mInterface.RepeatNullableStringArray(emptyValue));
            Assert.assertArrayEquals(value, mInterface.RepeatNullableStringArray(value));
        }
        {
            String[] emptyValue = {};
            String[] value = {"", "aoeu", null, "brb"};
            String[] out1 = new String[value.length];
            String[] out2 = mInterface.DoubleRepeatNullableStringArray(value, out1);

            Assert.assertArrayEquals(value, out1);
            Assert.assertArrayEquals(value, out2);
        }
    }

    @Test
    public void testGetLastItem() throws RemoteException {
        Foo foo = new Foo();
        foo.d = new Bar();
        foo.e = new Bar();
        foo.f = 15;

        assertEquals(foo.f, mInterface.getF(foo));
    }

    @Test
    public void testRepeatFoo() throws RemoteException {
        Foo foo = new Foo();

        foo.a = "NEW FOO";
        foo.b = 57;

        foo.d = new Bar();
        foo.d.b = "a";

        foo.e = new Bar();
        foo.e.d = 99;

        Foo repeatedFoo = mInterface.repeatFoo(foo);

        assertEquals(foo.a, repeatedFoo.a);
        assertEquals(foo.b, repeatedFoo.b);
        assertEquals(foo.d.b, repeatedFoo.d.b);
        assertEquals(foo.e.d, repeatedFoo.e.d);
    }

    @Test
    public void testRenameFoo() throws RemoteException {
        Foo foo = new Foo();
        foo.d = new Bar();
        foo.e = new Bar();
        mInterface.renameFoo(foo, "MYFOO");
        assertEquals("MYFOO", foo.a);
    }
    @Test
    public void testRenameBar() throws RemoteException {
        Foo foo = new Foo();
        foo.d = new Bar();
        foo.e = new Bar();
        mInterface.renameBar(foo, "MYBAR");
        assertEquals("MYBAR", foo.d.a);
    }
}
