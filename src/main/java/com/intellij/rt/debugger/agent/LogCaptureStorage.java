package com.intellij.rt.debugger.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
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
    private static boolean STDOUT_CAPTURE_ENABLED;

    // It's used by the debugger.
    static final AtomicLong EVENT_COUNTER = new AtomicLong();

    // It contains events that are waiting to be flushed.
    // New ones could be added concurrently.
    // They can also be flushed concurrently, leading to sending the same events multiple times.
    // It's ok and is handed by the debugger using IDs.
    // Event is removed from the queue only after it's guaranteed to be received by the debugger.
    static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();

    static final AtomicLong LAST_FLUSHED_EVENT_ID = new AtomicLong(-1);
    static final AtomicLong LAST_LOGGING_BREAKPOINT_EVENT_ID = new AtomicLong(-1);

    static class Event {
        public static final byte STD_OUTPUT_TYPE = 0;
        public static final byte LOGGING_BREAKPOINT_TYPE = 1;

        public final long id;
        public final byte type;
        public final byte[] payload;

        public Event(long id, byte type, byte[] payload) {
            this.id = id;
            this.type = type;
            this.payload = payload;
        }
    }

    private static final FileDescriptor FD_OUT = FileDescriptor.out;
    private static final FileDescriptor FD_ERR = FileDescriptor.err;

    private static final int MAX_STACK_DEPTH = 100; // It should be enough, we usually need only a few first frames.


    private static boolean batchingSchedulerStarted;
    static ArrayList<String> outputWrittenDumpForTests = null;

    public static boolean init(Properties properties, boolean logCaptureEnabled) {
        ENABLED = true;
        STDOUT_CAPTURE_ENABLED = logCaptureEnabled;
        BATCHING_ENABLED = Boolean.parseBoolean(properties.getProperty(BATCHING_ENABLED_PROPERTY, "true"));
        MAX_BATCHED_EVENTS_COUNT = Integer.parseInt(properties.getProperty(BATCHING_MAX_EVENTS_PROPERTY, "100"));
        if (BATCHING_ENABLED && !batchingSchedulerStarted) {
            batchingSchedulerStarted = true;

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

    private static long createNextEventId(int eventType) {
        if (!BATCHING_ENABLED) return -1;
        long id = EVENT_COUNTER.getAndIncrement();
        if (eventType == Event.LOGGING_BREAKPOINT_TYPE) {
            setIfGreater(LAST_LOGGING_BREAKPOINT_EVENT_ID, id);
        }
        return id;
    }

    public static void capture(FileDescriptor fd, byte[] bytes) {
        capture(fd, bytes, 0, bytes.length);
    }

    public static void capture(FileDescriptor fd, byte[] bytes, int off, int len) {
        if (!ENABLED || CAPTURING.get()) return;
        CAPTURING.set(true);
        try {
            if (fd != FD_OUT && fd != FD_ERR) return;
            if (len == 0) return;

            // Avoid logging breakpoint's output reorder with stdout.
            if (hasBatchedLoggingBreakpointEvents()) {
                flushBatchedData();
            }
            if (!STDOUT_CAPTURE_ENABLED) return;

            long id = createNextEventId(Event.STD_OUTPUT_TYPE);
            ByteArrayOutputStream bas = new ByteArrayOutputStream(); // no need to close it
            try (DataOutputStream dos = new DataOutputStream(bas)) {
                encodeMessageAndCurrentStacks(dos, bytes, off, len);
            }
            byte[] payload = bas.toByteArray();
            captureEvent(new Event(id, Event.STD_OUTPUT_TYPE, payload));
        } catch (Throwable e) {
            handleException(e);
        } finally {
            CAPTURING.set(false);
        }
    }

    private static boolean hasBatchedLoggingBreakpointEvents() {
        return LAST_LOGGING_BREAKPOINT_EVENT_ID.get() > LAST_FLUSHED_EVENT_ID.get();
    }

    private static void captureEvent(Event event) throws IOException {
        if (BATCHING_ENABLED) {
            EVENTS.add(event);
            flushBatchedDataIfMoreThan(MAX_BATCHED_EVENTS_COUNT);
        } else {
            packAndSend(Collections.singletonList(event));
        }
    }

    private static void encodeMessageAndCurrentStacks(DataOutputStream dos, byte[] bytes, int off, int len) throws IOException {
        List<StackTraceElement> regularStack = CaptureStorage.getCurrentStackTraceWithoutAgentFrames();
        List<StackTraceElement> capturedStack = CaptureStorage.getCurrentCapturedStack(MAX_STACK_DEPTH - regularStack.size());
        dos.writeInt(len);
        dos.write(bytes, off, len);
        CaptureStorage.writeAsyncStackTraceToStream(regularStack, dos);
        if (capturedStack != null) {
            CaptureStorage.writeAsyncStackTraceElementToStream(CaptureStorage.ASYNC_STACK_ELEMENT, dos);
            CaptureStorage.writeAsyncStackTraceToStream(capturedStack, dos);
        }
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
        long lastFlushedId = findMaxId(eventsSnapshot);
        setIfGreater(LAST_FLUSHED_EVENT_ID, lastFlushedId);
    }

    private static void packAndSend(Collection<Event> events) throws IOException {
        assert !events.isEmpty();

        ByteArrayOutputStream bas = new ByteArrayOutputStream(); // no need to close it
        try (GZIPOutputStream gos = new GZIPOutputStream(bas);
             DataOutputStream dos = new DataOutputStream(gos)) {
            dos.writeInt(events.size());
            for (Event event : events) {
                dos.writeLong(event.id);
                dos.writeByte(event.type);
                byte[] bytes = event.payload;
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
        }
        // ensure to close the gzip stream before extracting compressed data.
        String packed = bas.toString(StandardCharsets.ISO_8859_1.name());
        outputWritten(packed);
    }

    // It's used by the debugger.
    @SuppressWarnings("unused")
    private static void outputWritten(String captured) {
        if (outputWrittenDumpForTests != null) {
            outputWrittenDumpForTests.add(captured);
        }
    }

    // It's used in instrumentation.
    public static void loggingBreakpointHit(int instrumentationId, String message) {
        if (!ENABLED || CAPTURING.get()) return;
        CAPTURING.set(true);
        try {
            long id = createNextEventId(Event.LOGGING_BREAKPOINT_TYPE);
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream bas = new ByteArrayOutputStream(); // no need to close it
            try (DataOutputStream dos = new DataOutputStream(bas)) {
                dos.writeInt(instrumentationId);
                encodeMessageAndCurrentStacks(dos, messageBytes, 0, messageBytes.length);
            }
            byte[] payload = bas.toByteArray();
            captureEvent(new Event(id, Event.LOGGING_BREAKPOINT_TYPE, payload));
        } catch (Throwable e) {
            handleException(e);
        } finally {
            CAPTURING.set(false);
        }
    }

    private static long findMaxId(ArrayList<Event> events) {
        long lastFlushedId = -1;
        for (int i = events.size() - 1; i >= 0; i--) {
            long id = events.get(i).id;
            if (id > lastFlushedId) {
                lastFlushedId = id;
            }
        }
        return lastFlushedId;
    }

    private static void setIfGreater(AtomicLong maxValue, long newValue) {
        while (true) {
            long current = maxValue.get();
            if (current >= newValue || maxValue.compareAndSet(current, newValue)) {
                break;
            }
        }
    }
}
