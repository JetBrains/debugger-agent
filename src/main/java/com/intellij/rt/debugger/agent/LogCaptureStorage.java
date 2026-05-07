package com.intellij.rt.debugger.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

public class LogCaptureStorage {

    private static boolean ENABLED;

    // To prevent capturing during capturing.
    private final static ThreadLocal<Boolean> CAPTURING = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    static final String BATCHING_ENABLED_PROPERTY = "logCaptureBatchingEnabled";
    static final String BATCHING_FLUSH_PERIOD_PROPERTY = "logCaptureBatchingFlushPeriod";
    static final String BATCHING_MAX_EVENTS_PROPERTY = "logCaptureBatchingMaxEvents";

    private static boolean BATCHING_ENABLED;
    private static int MAX_BATCHED_EVENTS_COUNT;

    // It's used by the debugger.
    private static final AtomicLong EVENT_COUNTER = new AtomicLong();

    // It contains events that are waiting to be flushed.
    // New ones could be added concurrently.
    // They can also be flushed concurrently, leading to sending the same events multiple times.
    // It's ok and is handed by the debugger using IDs.
    // Event is removed from the queue only after it's guaranteed to be received by the debugger.
    private static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();

    private static class Event {
        public final long id;
        public final byte[] payload;

        public Event(long id, byte[] payload) {
            this.id = id;
            this.payload = payload;
        }
    }

    // See FileDescriptor for constants
    private static final int FD_OUT = 1;
    private static final int FD_ERR = 2;

    private static final int MAX_STACK_DEPTH = 100; // It should be enough, we usually need only a few first frames.

    public static boolean init(Properties properties) {
        ENABLED = true;
        BATCHING_ENABLED = Boolean.parseBoolean(properties.getProperty(BATCHING_ENABLED_PROPERTY, "true"));
        if (BATCHING_ENABLED) {
            MAX_BATCHED_EVENTS_COUNT = Integer.parseInt(
                    properties.getProperty(BATCHING_MAX_EVENTS_PROPERTY, "100"));

            final Runnable flushAction = new Runnable() {
                @Override
                public void run() {
                    CAPTURING.set(true);
                    try {
                        flushBatchedData();
                    } catch (Throwable e) {
                        handleException(e);
                    } finally {
                        CAPTURING.set(false);
                    }
                }
            };

            long flushPeriod = Long.parseLong(
                    properties.getProperty(BATCHING_FLUSH_PERIOD_PROPERTY, "100"));
            DebuggerAgent.SCHEDULED_EXECUTOR_SERVICE.scheduleWithFixedDelay(
                    flushAction, flushPeriod, flushPeriod, TimeUnit.MILLISECONDS);

            Runtime.getRuntime().addShutdownHook(
                    new Thread(flushAction, "IntelliJ Debugger Shutdown Log Flush Thread"));
        }
        return true;
    }

    public static void capture(int fd, byte[] bytes) {
        capture(fd, bytes, 0, bytes.length);
    }

    public static void capture(int fd, byte[] bytes, int off, int len) {
        if (!ENABLED || CAPTURING.get()) return;
        CAPTURING.set(true);
        try {
            if (fd != FD_OUT && fd != FD_ERR) return;
            if (len == 0) return;

            boolean isErr = fd == FD_ERR;

            List<StackTraceElement> regularStack = CaptureStorage.getCurrentStackTraceWithoutAgentFrames();
            List<StackTraceElement> capturedStack = CaptureStorage.getCurrentCapturedStack(MAX_STACK_DEPTH - regularStack.size());

            byte[] captured = encodeMessageAndStacks(bytes, off, len, isErr, regularStack, capturedStack);
            captureEvent(captured);

        } catch (Throwable e) {
            handleException(e);
        } finally {
            CAPTURING.set(false);
        }
    }

    private static void captureEvent(byte[] captured) throws IOException {
        if (BATCHING_ENABLED) {
            long id = EVENT_COUNTER.getAndIncrement();
            Event event = new Event(id, captured);
            EVENTS.add(event);
            flushBatchedDataIfMoreThan(MAX_BATCHED_EVENTS_COUNT);
        } else {
            packAndSend(Collections.singletonList(new Event(-1, captured)));
        }
    }

    private static byte[] encodeMessageAndStacks(byte[] bytes, int off, int len,
                                                 boolean isErr,
                                                 List<StackTraceElement> regularStack,
                                                 List<StackTraceElement> capturedStack) throws IOException {
        ByteArrayOutputStream bas = new ByteArrayOutputStream(); // no need to close it
        try (DataOutputStream dos = new DataOutputStream(bas)) {
            dos.writeInt(len);
            dos.write(bytes, off, len);
            dos.writeBoolean(isErr);
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

    // It's used by the debugger.
    private static void flushBatchedData() throws IOException {
        flushBatchedDataIfMoreThan(0);
    }

    private static void flushBatchedDataIfMoreThan(int eventsCountLimit) throws IOException {
        if (EVENTS.size() <= eventsCountLimit) return;
        ArrayList<Event> eventsSnapshot = new ArrayList<>(EVENTS);
        if (eventsSnapshot.size() <= eventsCountLimit) return;
        packAndSend(eventsSnapshot);
        EVENTS.removeAll(new HashSet<>(eventsSnapshot));
    }

    private static void packAndSend(Collection<Event> events) throws IOException {
        assert !events.isEmpty();

        ByteArrayOutputStream bas = new ByteArrayOutputStream(); // no need to close it
        try (GZIPOutputStream gos = new GZIPOutputStream(bas);
             DataOutputStream dos = new DataOutputStream(gos)) {
            dos.writeInt(events.size());
            for (Event event : events) {
                dos.writeLong(event.id);
                byte[] bytes = event.payload;
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
        }
        // ensure to close the gzip stream before extracting compressed data.
        String packed = bas.toString(StandardCharsets.ISO_8859_1.name());
        outputWritten(packed);
    }

    static ArrayList<String> outputWrittenDumpForTests = null;

    // It's used by the debugger.
    @SuppressWarnings("unused")
    private static void outputWritten(String captured) {
        if (outputWrittenDumpForTests != null) {
            outputWrittenDumpForTests.add(captured);
        }
    }

    // It's used by the debugger and instrumentation.
    @SuppressWarnings("unused")
    public static void loggingBreakpointHit(int instrumentationId, String message) {
    }

}
