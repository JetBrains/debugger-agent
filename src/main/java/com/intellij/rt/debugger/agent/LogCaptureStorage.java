package com.intellij.rt.debugger.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

public class LogCaptureStorage {

    private static boolean ENABLED;

    // To prevent capturing during capturing.
    private static boolean CAPTURING;

    private static boolean BATCHING_ENABLED;
    private static final long MAX_BATCH_SIZE = 1024 * 1024; // 1MB
    private static long BATCH_SIZE = 0;
    private static final List<byte[]> BATCHED_OUTPUT = new ArrayList<>();

    private static final FileDescriptor FD_OUT = FileDescriptor.out;
    private static final FileDescriptor FD_ERR = FileDescriptor.err;

    private static final int MAX_STACK_DEPTH = 100; // It should be enough, we usually need only a few first frames.

    public static boolean init(Properties properties) {
        ENABLED = true;
        BATCHING_ENABLED = Boolean.parseBoolean(properties.getProperty("logCaptureBatchingEnabled", "true"));
        if (BATCHING_ENABLED) {
            Runnable flushRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        flush();
                    } catch (Throwable e) {
                        handleException(e);
                    }
                }
            };
            DebuggerAgent.SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(flushRunnable, 100, 100, TimeUnit.MILLISECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(flushRunnable));
        }
        return true;
    }

    public static void capture(FileDescriptor fd, byte[] bytes, int off, int len) {
        if (!ENABLED || CAPTURING) return;
        CAPTURING = true;
        try {
            if (fd != FD_OUT && fd != FD_ERR) return;
            if (len == 0) return;

            List<StackTraceElement> regularStack = CaptureStorage.getCurrentStackTraceWithoutAgentFrames();
            List<StackTraceElement> capturedStack = CaptureStorage.getCurrentCapturedStack(MAX_STACK_DEPTH - regularStack.size());

            byte[] captured = encodeMessageAndStacks(bytes, off, len, regularStack, capturedStack);

            if (BATCHING_ENABLED) {
                synchronized (LogCaptureStorage.class) {
                    BATCHED_OUTPUT.add(captured);
                    BATCH_SIZE += captured.length;
                    if (BATCH_SIZE > MAX_BATCH_SIZE) {
                        flush();
                    }
                }
            } else {
                String batched = encodeBatchedData(Collections.singletonList(captured));
                if (batched != null) {
                    outputWritten(batched);
                }
            }

        } catch (Exception e) {
            handleException(e);
        } finally {
            CAPTURING = false;
        }
    }

    public static void capture(FileDescriptor fd, byte[] bytes) {
        capture(fd, bytes, 0, bytes.length);
    }

    static byte[] encodeMessageAndStacks(byte[] bytes, int off, int len,
                                         List<StackTraceElement> regularStack,
                                         List<StackTraceElement> capturedStack) throws IOException {
        ByteArrayOutputStream bas = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(bas))) {
            dos.writeInt(len);
            dos.write(bytes, off, len);
            CaptureStorage.writeAsyncStackTraceToStream(regularStack, dos);
            if (capturedStack != null) {
                CaptureStorage.writeAsyncStackTraceElementToStream(CaptureStorage.ASYNC_STACK_ELEMENT, dos);
                CaptureStorage.writeAsyncStackTraceToStream(capturedStack, dos);
            }
        }
        return bas.toByteArray();
    }

    private static void handleException(Throwable e) {
        ENABLED = false;
        System.err.println("Debugger agent, log capture: cannot capture logging");
        e.printStackTrace(System.err);
    }

    private static void flush() {
        String batched = prepareBatchedDataAndClear();
        if (batched != null) {
            outputWritten(batched);
        }
    }

    // It's used by the debugger.
    private static String prepareBatchedDataAndClear() {
        synchronized (LogCaptureStorage.class) {
            String batched = encodeBatchedData(BATCHED_OUTPUT);
            BATCHED_OUTPUT.clear();
            BATCH_SIZE = 0;
            return batched;
        }
    }

    private static String encodeBatchedData(List<byte[]> batchedData) {
        String batched;
        if (batchedData.isEmpty()) return null;
        try (ByteArrayOutputStream bas = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bas)) {
            dos.writeInt(batchedData.size());
            for (byte[] bytes : batchedData) {
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
            batched = bas.toString(StandardCharsets.ISO_8859_1.name());
        } catch (Exception e) {
            handleException(e);
            return null;
        }
        return batched;
    }

    // It's used by the debugger.
    @SuppressWarnings("unused")
    private static void outputWritten(String captured) {
    }

    // It's used by the debugger and instrumentation.
    @SuppressWarnings("unused")
    public static void loggingBreakpointHit(int instrumentationId, String message) {
    }

}
