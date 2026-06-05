package com.intellij.rt.debugger.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

//Throwable capacity before getStackTrace()
//Bytes per Throwable: 2809
//Throwables fitting 5 MiB: 1866
//
//Throwable capacity after getStackTrace()
//Bytes per Throwable: 8225
//Throwables fitting 5 MiB: 637
//
//Captured stack bytes capacity
//Bytes per stack: 9142
//Stacks fitting 5 MiB: 573
//
//Packed batch capacity
//Packed bytes per event: 65
//Events fitting 5 MiB: 80896
public class ThrowableCapacityOverhead {
    private static final long BYTES_LIMIT = 5L * 1024L * 1024L;
    private static final int MAX_STACK_DEPTH = 100;

    private static volatile Object retained;
    private static volatile int checksumSink;

    private static Throwable recursiveThrowable(int depth) {
        if (depth > 0) return recursiveThrowable(depth - 1);
        return new Throwable();
    }

    private static byte[] recursiveCurrentStacks(int depth) throws IOException {
        if (depth > 0) return recursiveCurrentStacks(depth - 1);
        return writeCapturedStack();
    }

    private static void recursiveCapture(int depth, int repeats, byte[] message) {
        if (depth > 0) {
            recursiveCapture(depth - 1, repeats, message);
            return;
        }
        for (int i = 0; i < repeats; i++) {
            LogCaptureStorage.capture(FileDescriptor.out, message);
        }
    }

    private static int fillThrowables(Throwable[] throwables, int stackDepth, boolean materializeStackTrace) {
        int agg = 0;
        for (int i = 0; i < throwables.length; i++) {
            Throwable throwable = recursiveThrowable(stackDepth);
            if (materializeStackTrace) {
                agg ^= throwable.getStackTrace().length;
            }
            else {
                agg ^= System.identityHashCode(throwable);
            }
            throwables[i] = throwable;
        }
        return agg;
    }

    private static int fillCurrentStacks(byte[][] currentStacks, int stackDepth) throws IOException {
        int agg = 0;
        for (int i = 0; i < currentStacks.length; i++) {
            byte[] stackBytes = recursiveCurrentStacks(stackDepth);
            agg ^= stackBytes.length;
            currentStacks[i] = stackBytes;
        }
        return agg;
    }

    private static byte[] writeCapturedStack() throws IOException {
        ByteArrayOutputStream bas = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bas)) {
            CaptureStorage.writeCapturedStackToStream(new Throwable(), CaptureStorage.getCurrentCapturedStack(), MAX_STACK_DEPTH, dos);
        }
        return bas.toByteArray();
    }

    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void forceGc() throws InterruptedException {
        System.gc();
        Thread.sleep(100);
        System.gc();
        Thread.sleep(100);
    }

    private static void measure(String title, int stackDepth, int repeats, boolean materializeStackTrace) throws Exception {
        retained = null;
        forceGc();
        long usedBefore = usedMemory();

        Throwable[] throwables = new Throwable[repeats];
        retained = throwables;

        checksumSink = fillThrowables(throwables, stackDepth, materializeStackTrace);
        forceGc();
        long usedAfter = usedMemory();
        long usedBytes = usedAfter - usedBefore;
        double bytesPerThrowable = (double) usedBytes / repeats;

        System.out.println(title);
        System.out.println("Bytes per Throwable: " + Math.round(bytesPerThrowable));
        System.out.println("Throwables fitting 5 MiB: " + (long) (BYTES_LIMIT / bytesPerThrowable));
    }

    private static void measureWithCurrentStacks(int stackDepth, int repeats) throws Exception {
        retained = null;
        forceGc();
        long usedBefore = usedMemory();

        byte[][] currentStacks = new byte[repeats][];
        retained = currentStacks;

        checksumSink = fillCurrentStacks(currentStacks, stackDepth);
        forceGc();
        long usedAfter = usedMemory();
        long usedBytes = usedAfter - usedBefore;
        double bytesPerThrowable = (double) usedBytes / repeats;

        System.out.println("Captured stack bytes capacity");
        System.out.println("Bytes per stack: " + Math.round(bytesPerThrowable));
        System.out.println("Stacks fitting 5 MiB: " + (long) (BYTES_LIMIT / bytesPerThrowable));
    }

    private static void measureAfterBatchZipping(int stackDepth, int repeats) throws Exception {
        resetLogCaptureStorage();

        Properties properties = new Properties();
        properties.put(LogCaptureStorage.BATCHING_ENABLED_PROPERTY, "true");
        properties.put(LogCaptureStorage.BATCHING_FLUSH_PERIOD_PROPERTY, "999999999");
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "1000");
        properties.put(LogCaptureStorage.BATCHING_MAX_PACKED_BYTES_PROPERTY, String.valueOf(Long.MAX_VALUE));
        LogCaptureStorage.init(properties, true);

        recursiveCapture(stackDepth, repeats, "stdout message\n".getBytes(StandardCharsets.UTF_8));
        LogCaptureStorage.packBatchedData();

        double packedBytesPerEvent = (double) LogCaptureStorage.PACKED_BATCHES_BYTES.get() / repeats;

        System.out.println("Packed batch capacity");
        System.out.println("Packed bytes per event: " + Math.round(packedBytesPerEvent));
        System.out.println("Events fitting 5 MiB: " + (long) (BYTES_LIMIT / packedBytesPerEvent));
    }

    private static void resetLogCaptureStorage() {
        LogCaptureStorage.EVENT_COUNTER.set(0);
        LogCaptureStorage.LAST_FLUSHED_EVENT_ID.set(-1);
        LogCaptureStorage.LAST_PACKED_EVENT_ID.set(-1);
        LogCaptureStorage.LAST_LOGGING_BREAKPOINT_EVENT_ID.set(-1);
        LogCaptureStorage.EVENTS.clear();
        LogCaptureStorage.PACKED_BATCHES.clear();
        LogCaptureStorage.PACKED_BATCHES_BYTES.set(0);
        LogCaptureStorage.outputWrittenDumpForTests = null;
    }

    public static void main(String[] args) throws Exception {
        int repeats = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;
        int stackDepth = args.length > 1 ? Integer.parseInt(args[1]) : 100;

        measure("Throwable capacity before getStackTrace()", stackDepth, repeats, false);
        System.out.println();
        measure("Throwable capacity after getStackTrace()", stackDepth, repeats, true);
        System.out.println();
        measureWithCurrentStacks(stackDepth, repeats);
        System.out.println();
        measureAfterBatchZipping(stackDepth, repeats);
    }

}
