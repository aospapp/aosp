/* GENERATED SOURCE. DO NOT MODIFY. */
package com.android.org.conscrypt.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * @hide This class is not part of the Android public SDK API
 */
@RunWith(JUnit4.class)
public class OptionalMethodTest {

    @Test
    public void workingMethod() {
        OptionalMethod substring =
                new OptionalMethod(String.class, "substring", int.class, int.class);
        assertNotNull(substring);

        assertEquals("put", substring.invoke("input", 2, 5));
    }

    @Test
    public void nullClass() {
        OptionalMethod substring =
                new OptionalMethod(null, "substring", int.class, int.class);
        assertNotNull(substring);

        assertNull(substring.invoke("input", 2, 5));
    }

    @Test
    public void nullMethodName() {
        assertThrows(NullPointerException.class,
                () -> new OptionalMethod(String.class, null, int.class, int.class));
    }

    @Test
    public void nullArgumentClasses() {
        OptionalMethod substring = new OptionalMethod(String.class, "substring", int.class, null);
        assertNotNull(substring);

        assertNull(substring.invoke("input", 2, 5));
    }

    @Test
    public void noSuchMethodName() {
        OptionalMethod subwrong =
                new OptionalMethod(null, "subwrong", int.class, int.class);
        assertNotNull(subwrong);

        assertNull(subwrong.invoke("input", 2, 5));
    }

    @Test
    public void noSuchMethodArgs() {
        OptionalMethod subwrong =
                new OptionalMethod(null, "substring", long.class, byte[].class);
        assertNotNull(subwrong);

        assertNull(subwrong.invoke("input", 2, 5));
    }

    @Test
    public void nullReceiver() {
        OptionalMethod substring =
                new OptionalMethod(String.class, "substring", int.class, int.class);
        assertNotNull(substring);

        assertEquals(null, substring.invoke(null, 2, 5));
    }
}
