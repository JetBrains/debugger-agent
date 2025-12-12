package com.intellij.rt.debugger.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class LogCaptureStorage {

    private static boolean ENABLED;

    // To prevent capturing during capturing.
    private static boolean CAPTURING;

    private static final FileDescriptor FD_OUT = FileDescriptor.out;
    private static final FileDescriptor FD_ERR = FileDescriptor.err;

    private static final int MAX_STACK_DEPTH = 100; // It should be enough, we usually need only a few first frames.

    public static boolean init() {
        ENABLED = true;
        return true;
    }

    public static void capture(FileDescriptor fd, byte[] bytes, int off, int len) {
        if (!ENABLED || CAPTURING) return;
        CAPTURING = true;
        try {
            if (fd != FD_OUT && fd != FD_ERR) return;
            if (len == 0) return;

            List<StackTraceElement> regularStack = Arrays.asList(Thread.currentThread().getStackTrace());
            List<StackTraceElement> capturedStack = CaptureStorage.getCurrentCapturedStack(MAX_STACK_DEPTH - regularStack.size());

            String captured;
            try (ByteArrayOutputStream bas = new ByteArrayOutputStream();
                 DataOutputStream dos = new DataOutputStream(bas)) {
                dos.writeInt(len);
                dos.write(bytes, off, len);
                CaptureStorage.writeAsyncStackTraceToStream(regularStack, dos);
                if (capturedStack != null) {
                    CaptureStorage.writeAsyncStackTraceElementToStream(CaptureStorage.ASYNC_STACK_ELEMENT, dos);
                    CaptureStorage.writeAsyncStackTraceToStream(capturedStack, dos);
                }
                captured = bas.toString(StandardCharsets.ISO_8859_1.name());
            }

            save(captured);

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

    // It's used by the debugger.
    private static void save(String captured) {
    }

}
