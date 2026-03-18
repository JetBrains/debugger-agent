package com.intellij.rt.debugger.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    public static boolean init() {
        ENABLED = true;
        BATCHING_ENABLED = SuspendHelper.addPeriodicListener(new Runnable() {
            @Override
            public void run() {
                if (!BATCHING_ENABLED) return;
                try {
                    flush();
                } catch (Throwable e) {
                    BATCHING_ENABLED = false;
                    System.err.println("Error while flushing log capture:");
                    e.printStackTrace(System.err);
                }
            }
        });
        if (BATCHING_ENABLED) {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    flush();
                }
            }));
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

            byte[] captured;
            try (ByteArrayOutputStream bas = new ByteArrayOutputStream();
                 DataOutputStream dos = new DataOutputStream(bas)) {
                dos.writeInt(len);
                dos.write(bytes, off, len);
                CaptureStorage.writeAsyncStackTraceToStream(regularStack, dos);
                if (capturedStack != null) {
                    CaptureStorage.writeAsyncStackTraceElementToStream(CaptureStorage.ASYNC_STACK_ELEMENT, dos);
                    CaptureStorage.writeAsyncStackTraceToStream(capturedStack, dos);
                }
                captured = bas.toByteArray();
            }

            if (BATCHING_ENABLED) {
                synchronized (LogCaptureStorage.class) {
                    BATCHED_OUTPUT.add(captured);
                    BATCH_SIZE += captured.length;
                    if (BATCH_SIZE > MAX_BATCH_SIZE) {
                        flush();
                    }
                }
            } else {
                String batched;
                try (ByteArrayOutputStream bas = new ByteArrayOutputStream();
                     DataOutputStream dos = new DataOutputStream(bas)) {
                    dos.writeInt(1);
                    dos.writeInt(captured.length);
                    dos.write(captured);
                    batched = bas.toString(StandardCharsets.ISO_8859_1.name());
                } catch (Exception e) {
                    handleException(e);
                    return;
                }
                outputWritten(batched);
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

    private static void handleException(Exception e) {
        ENABLED = false;
        System.err.println("Debugger agent, log capture: cannot capture logging");
        e.printStackTrace(System.err);
    }

    private static void flush() {
        String batched;
        synchronized (LogCaptureStorage.class) {
            if (BATCHED_OUTPUT.isEmpty()) return;
            try (ByteArrayOutputStream bas = new ByteArrayOutputStream();
                 DataOutputStream dos = new DataOutputStream(bas)) {
                dos.writeInt(BATCHED_OUTPUT.size());
                for (byte[] bytes : BATCHED_OUTPUT) {
                    dos.writeInt(bytes.length);
                    dos.write(bytes);
                }
                batched = bas.toString(StandardCharsets.ISO_8859_1.name());
            } catch (Exception e) {
                handleException(e);
                return;
            }
            BATCHED_OUTPUT.clear();
            BATCH_SIZE = 0;
        }
        outputWritten(batched);
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
