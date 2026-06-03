package com.intellij.rt.debugger.agent;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class ThrowableInternerTest {
    private static final Field BACKTRACE_FIELD = findBacktraceField();
    private static final Field STACK_TRACE_FIELD = findField("stackTrace");

    @Before
    public void setUp() {
        ThrowableInterner.clear();
    }

    @Test
    public void internsEqualUnexpandedThrowablesFromSameCallSite() throws Exception {
        assumeTrue("Throwable backtrace field should be accessible", BACKTRACE_FIELD != null);
        assumeTrue("Throwable stackTrace field should be accessible", STACK_TRACE_FIELD != null);
        Throwable[] throwables = new Throwable[2];
        for (int i = 0; i < throwables.length; i++) {
            throwables[i] = createThrowable();
        }
        Throwable first = throwables[0];
        Throwable second = throwables[1];

        Throwable internedFirst = intern(first);
        Throwable internedSecond = intern(second);

        assertSame(internedFirst, internedSecond);
        assertFalse(isStackTraceExpanded(first));
        assertFalse(isStackTraceExpanded(second));
        assertEquals(1, ThrowableInterner.size());
    }

    @Test
    public void clearDropsInternedThrowables() throws Exception {
        assumeTrue("Throwable backtrace field should be accessible", BACKTRACE_FIELD != null);

        Throwable[] internedThrowables = new Throwable[2];
        for (int i = 0; i < internedThrowables.length; i++) {
            if (i > 0) {
                ThrowableInterner.clear();
            }
            internedThrowables[i] = intern(createThrowable());
            assertEquals(1, ThrowableInterner.size());
        }

        assertNotSame(internedThrowables[0], internedThrowables[1]);
    }

    @Test
    public void internsEqualLongArrayBacktraces() {
        Throwable first = createThrowable();
        Throwable second = createThrowable();

        Throwable internedFirst = ThrowableInterner.intern(first, new long[]{1L, 2L, 3L});
        Throwable internedSecond = ThrowableInterner.intern(second, new long[]{1L, 2L, 3L});

        assertSame(internedFirst, internedSecond);
        assertEquals(1, ThrowableInterner.size());
    }

    @Test
    public void internsEqualIntArrayBacktraces() {
        Throwable first = createThrowable();
        Throwable second = createThrowable();

        Throwable internedFirst = ThrowableInterner.intern(first, new int[]{1, 2, 3});
        Throwable internedSecond = ThrowableInterner.intern(second, new int[]{1, 2, 3});

        assertSame(internedFirst, internedSecond);
        assertEquals(1, ThrowableInterner.size());
    }

    @Test
    public void keepsPrimitiveBacktraceTypesSeparate() {
        Throwable longBacktraceThrowable = createThrowable();
        Throwable intBacktraceThrowable = createThrowable();

        Throwable internedLongBacktrace = ThrowableInterner.intern(longBacktraceThrowable, new long[]{1L, 2L, 3L});
        Throwable internedIntBacktrace = ThrowableInterner.intern(intBacktraceThrowable, new int[]{1, 2, 3});

        assertSame(longBacktraceThrowable, internedLongBacktrace);
        assertSame(intBacktraceThrowable, internedIntBacktrace);
        assertEquals(2, ThrowableInterner.size());
    }

    @Test
    public void doesNotCallVirtualMethodsOnThrowableSubclasses() {
        Throwable throwable = new ThrowableWithFailingMessage();

        Throwable interned = ThrowableInterner.intern(throwable, new long[]{1L, 2L, 3L});

        assertSame(throwable, interned);
        assertEquals(0, ThrowableInterner.size());
    }

    @Test
    public void doesNotInternPlainThrowablesWithMessageOrCause() {
        Throwable withMessage = new Throwable("message");
        Throwable withCause = new Throwable(new Throwable());

        assertSame(withMessage, ThrowableInterner.intern(withMessage, new long[]{1L, 2L, 3L}));
        assertSame(withCause, ThrowableInterner.intern(withCause, new long[]{1L, 2L, 3L}));
        assertEquals(0, ThrowableInterner.size());
    }

    private static Throwable createThrowable() {
        return new Throwable();
    }

    private static class ThrowableWithFailingMessage extends Throwable {
        @Override
        public String getMessage() {
            throw new AssertionError("getMessage must not be called");
        }
    }

    private static Throwable intern(Throwable throwable) throws Exception {
        return ThrowableInterner.intern(throwable, getBacktrace(throwable));
    }

    private static Object getBacktrace(Throwable throwable) throws Exception {
        return BACKTRACE_FIELD.get(throwable);
    }

    private static boolean isStackTraceExpanded(Throwable throwable) throws Exception {
        Field field = STACK_TRACE_FIELD;
        assumeTrue("Throwable stackTrace field should be accessible", field != null);
        StackTraceElement[] stackTrace = (StackTraceElement[]) field.get(throwable);
        return stackTrace.length != 0;
    }

    private static Field findBacktraceField() {
        Field field = findField("backtrace");
        if (field != null) return field;
        return findField("walkback");
    }

    private static Field findField(String name) {
        try {
            Field field = Throwable.class.getDeclaredField(name);
            field.setAccessible(true);
            Object ignoredValue = field.get(new Throwable());
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
