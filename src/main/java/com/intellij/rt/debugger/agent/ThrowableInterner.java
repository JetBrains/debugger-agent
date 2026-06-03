package com.intellij.rt.debugger.agent;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Interns throwables using the raw VM backtrace captured by {@link ThrowableTransformer}
 * without calling {@link Throwable#getStackTrace()}.
 * <p>
 * This is a small debugger-agent copy of the approach from
 * <a href="https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/openapi/util/objectTree/ThrowableInterner.java">
 * com.intellij.openapi.util.objectTree.ThrowableInterner</a>.
 */
final class ThrowableInterner {
    private static final ThreadLocal<Boolean> CAPTURE_BACKTRACE = new ThreadLocal<>();
    private static final ThreadLocal<Object> CAPTURED_BACKTRACE = new ThreadLocal<>();
    private static final ConcurrentMap<BacktraceKey, Throwable> INTERNED_THROWABLES = new ConcurrentHashMap<>();
    private static volatile boolean ourEnabled = true;

    private ThrowableInterner() {
    }

    static Throwable createThrowable() {
        if (!ourEnabled) return new Throwable();
        CAPTURE_BACKTRACE.set(Boolean.TRUE);
        try {
            Throwable throwable = new Throwable();
            return intern(throwable, CAPTURED_BACKTRACE.get());
        } finally {
            CAPTURE_BACKTRACE.remove();
            CAPTURED_BACKTRACE.remove();
        }
    }

    static void captureBacktrace(Object backtrace) {
        if (!ourEnabled) return;
        try {
            Boolean isInsideCreateThrowable = CAPTURE_BACKTRACE.get();
            if (isInsideCreateThrowable == Boolean.TRUE) {
                CAPTURED_BACKTRACE.set(backtrace);
            }
        } catch (Throwable t) {
            disable("Debugger agent, throwable interner: cannot capture throwable backtrace", t);
        }
    }

    static Throwable intern(Throwable throwable, Object backtrace) {
        if (!ourEnabled) return throwable;
        if (backtrace == null) return throwable;
        // Log capture only interns plain new Throwable(); richer throwables are rare here and
        // would make equality slower because message/cause have to be compared recursively.
        if (throwable.getClass() != Throwable.class) return throwable;
        if (throwable.getMessage() != null || throwable.getCause() != null) return throwable;
        // HotSpot stores the raw backtrace as Object[]; OpenJ9 stores walkback PCs as long[] or int[].
        if (!isSupportedBacktrace(backtrace)) {
            String backtraceType = backtrace.getClass().getName();
            disable("Debugger agent, throwable interner: unsupported throwable backtrace type " + backtraceType);
            return throwable;
        }

        // The raw VM backtrace is captured by new Throwable(), unlike StackTraceElement[] which is
        // created lazily and relatively expensively by getStackTrace().
        BacktraceKey key = new BacktraceKey(throwable, backtrace);
        Throwable interned = INTERNED_THROWABLES.putIfAbsent(key, throwable);
        return interned == null ? throwable : interned;
    }

    static void clear() {
        INTERNED_THROWABLES.clear();
    }

    static int size() {
        return INTERNED_THROWABLES.size();
    }

    static void disable(String message) {
        disable(message, null);
    }

    private static final class BacktraceKey {
        private final Class<?> myThrowableClass;
        private final Object myBacktrace;
        private final int myHashCode;

        private BacktraceKey(Throwable throwable, Object backtrace) {
            myThrowableClass = throwable.getClass();
            myBacktrace = backtrace;
            myHashCode = computeHashCode(myThrowableClass, backtrace);
        }

        private static int computeHashCode(Class<?> throwableClass, Object backtrace) {
            int result = throwableClass.hashCode();
            result = 31 * result + backtraceHashCode(backtrace);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof BacktraceKey)) return false;

            BacktraceKey key = (BacktraceKey) obj;
            return myHashCode == key.myHashCode &&
                    myThrowableClass == key.myThrowableClass &&
                    backtracesEqual(myBacktrace, key.myBacktrace);
        }

        @Override
        public int hashCode() {
            return myHashCode;
        }
    }

    private static boolean isSupportedBacktrace(Object backtrace) {
        return backtrace instanceof Object[] || backtrace instanceof long[] || backtrace instanceof int[];
    }

    private static synchronized void disable(String message, Throwable cause) {
        if (!ourEnabled) return;
        ourEnabled = false;
        System.err.println(message);
        if (cause != null) {
            cause.printStackTrace(System.err);
        }
    }

    private static int backtraceHashCode(Object backtrace) {
        if (backtrace instanceof Object[]) return Arrays.deepHashCode((Object[]) backtrace);
        if (backtrace instanceof long[]) return Arrays.hashCode((long[]) backtrace);
        if (backtrace instanceof int[]) return Arrays.hashCode((int[]) backtrace);
        return 0;
    }

    private static boolean backtracesEqual(Object first, Object second) {
        if (first instanceof Object[] && second instanceof Object[]) {
            return Arrays.deepEquals((Object[]) first, (Object[]) second);
        }
        if (first instanceof long[] && second instanceof long[]) {
            return Arrays.equals((long[]) first, (long[]) second);
        }
        if (first instanceof int[] && second instanceof int[]) {
            return Arrays.equals((int[]) first, (int[]) second);
        }
        return false;
    }
}
