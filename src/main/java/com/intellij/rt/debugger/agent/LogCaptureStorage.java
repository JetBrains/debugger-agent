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
    static final String BATCHING_MAX_PACKED_BYTES_PROPERTY = "logCaptureBatchingMaxPackedBytes";
    private static final long DEFAULT_MAX_BATCHED_PACKED_BYTES = 5L * 1024L * 1024L;

    private static boolean BATCHING_ENABLED;
    private static int MAX_BATCHED_EVENTS_COUNT;
    private static long MAX_BATCHED_PACKED_BYTES;
    private static boolean STDOUT_CAPTURE_ENABLED;

    // It's used by the debugger.
    static final AtomicLong EVENT_COUNTER = new AtomicLong();

    // It contains raw events that are waiting to be packed.
    // New ones could be added concurrently.
    // Raw or packed data can be flushed concurrently, leading to sending the same events multiple times.
    // It's ok and is handled by the debugger using IDs.
    static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<PackedBatch> PACKED_BATCHES = new ConcurrentLinkedQueue<>();
    static final AtomicLong PACKED_BATCHES_BYTES = new AtomicLong();

    static final AtomicLong LAST_FLUSHED_EVENT_ID = new AtomicLong(-1);
    static final AtomicLong LAST_PACKED_EVENT_ID = new AtomicLong(-1);
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

    static class PackedBatch {
        public final byte[] data;
        public final long lastEventId;

        public PackedBatch(byte[] data, long lastEventId) {
            this.data = data;
            this.lastEventId = lastEventId;
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
        MAX_BATCHED_PACKED_BYTES = Long.parseLong(properties.getProperty(
                BATCHING_MAX_PACKED_BYTES_PROPERTY,
                String.valueOf(DEFAULT_MAX_BATCHED_PACKED_BYTES)));
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

            boolean isErr = fd == FD_ERR;
            long id = createNextEventId(Event.STD_OUTPUT_TYPE);
            ByteArrayOutputStream bas = new ByteArrayOutputStream(); // no need to close it
            try (DataOutputStream dos = new DataOutputStream(bas)) {
                dos.writeInt(len);
                dos.write(bytes, off, len);
                dos.writeBoolean(isErr);
                writeCurrentStacks(dos);
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

    private static void writeCurrentStacks(DataOutputStream dos) throws IOException {
        List<StackTraceElement> regularStack = CaptureStorage.getCurrentStackTraceWithoutAgentFrames();
        List<StackTraceElement> capturedStack = CaptureStorage.getCurrentCapturedStack(MAX_STACK_DEPTH - regularStack.size());
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

    /**
     * It's used by the debugger via evaluation.
     * This method intentionally does not drop the collected data because the return value may be collected
     * before it appears on the debugger side. The clearing happens in the periodic flush cycle.
     */
    static String packBatchedData() throws IOException {
        packRawEventsIfMoreThan(0);
        List<PackedBatch> packedBatchesSnapshot = new ArrayList<>(PACKED_BATCHES);
        if (packedBatchesSnapshot.isEmpty()) return null;
        return packPendingData(packedBatchesSnapshot);
    }

    private static void flushBatchedDataIfMoreThan(int eventsCountLimit) throws IOException {
        if (eventsCountLimit <= 0 || currentEventsSize() > eventsCountLimit) {
            packRawEventsIfMoreThan(eventsCountLimit);
        }

        flushPackedBatchesIfNeeded(false);
    }

    private static long currentEventsSize() {
        // This is an approximation, but eventsCountLimit is considered non-strict when it is not 0.
        // The exact size is checked below.
        // N.B. EVENTS.size() takes linear time, so it can be very slow.
        return EVENT_COUNTER.get() - 1 - LAST_PACKED_EVENT_ID.get();
    }

    private static void flushBatchedData() throws IOException {
        packRawEventsIfMoreThan(0);
        flushPackedBatchesIfNeeded(true);
    }

    private static void packRawEventsIfMoreThan(int eventsCountLimit) throws IOException {
        if (EVENTS.isEmpty()) return;
        List<Event> eventsSnapshot = new ArrayList<>(EVENTS);
        if (eventsSnapshot.size() > eventsCountLimit) {
            enqueuePackedBatch(eventsSnapshot);
            EVENTS.removeAll(new HashSet<>(eventsSnapshot));
        }
    }

    private static void enqueuePackedBatch(List<Event> events) throws IOException {
        if (events.isEmpty()) return;
        byte[] packed = packBytes(events);
        long lastPackedId = findMaxId(events);
        PACKED_BATCHES.add(new PackedBatch(packed, lastPackedId));
        PACKED_BATCHES_BYTES.addAndGet(packed.length);
        setIfGreater(LAST_PACKED_EVENT_ID, lastPackedId);
    }

    private static void flushPackedBatchesIfNeeded(boolean forceOutput) throws IOException {
        if (!forceOutput && PACKED_BATCHES_BYTES.get() <= MAX_BATCHED_PACKED_BYTES) return;
        packRawEventsIfMoreThan(0);
        List<PackedBatch> packedBatchesSnapshot = new ArrayList<>(PACKED_BATCHES);
        if (packedBatchesSnapshot.isEmpty()) return;

        outputWritten(packPendingData(packedBatchesSnapshot));
        removePackedBatches(packedBatchesSnapshot);
        setIfGreater(LAST_FLUSHED_EVENT_ID, findMaxPackedEventId(packedBatchesSnapshot));
    }

    private static void packAndSend(Collection<Event> events) throws IOException {
        outputWritten(packPendingData(Collections.singletonList(new PackedBatch(packBytes(events), -1))));
    }

    private static byte[] packBytes(Collection<Event> events) throws IOException {
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
        return bas.toByteArray();
    }

    private static String packPendingData(Collection<PackedBatch> packedBatches) throws IOException {
        ByteArrayOutputStream bas = new ByteArrayOutputStream(); // no need to close it
        try (DataOutputStream dos = new DataOutputStream(bas)) {
            dos.writeInt(packedBatches.size());
            for (PackedBatch batch : packedBatches) {
                dos.writeInt(batch.data.length);
                dos.write(batch.data);
            }
        }
        return bas.toString(StandardCharsets.ISO_8859_1.name());
    }

    // It's used by the debugger.
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
                dos.writeInt(messageBytes.length);
                dos.write(messageBytes);
                writeCurrentStacks(dos);
            }
            byte[] payload = bas.toByteArray();
            captureEvent(new Event(id, Event.LOGGING_BREAKPOINT_TYPE, payload));
        } catch (Throwable e) {
            handleException(e);
        } finally {
            CAPTURING.set(false);
        }
    }

    private static long findMaxId(List<Event> events) {
        long lastFlushedId = -1;
        for (int i = events.size() - 1; i >= 0; i--) {
            long id = events.get(i).id;
            if (id > lastFlushedId) {
                lastFlushedId = id;
            }
        }
        return lastFlushedId;
    }

    private static long findMaxPackedEventId(Collection<PackedBatch> packedBatches) {
        long result = -1;
        for (PackedBatch batch : packedBatches) {
            if (batch.lastEventId > result) {
                result = batch.lastEventId;
            }
        }
        return result;
    }

    private static void removePackedBatches(Collection<PackedBatch> packedBatches) {
        long removedBytes = 0;
        for (PackedBatch batch : packedBatches) {
            if (PACKED_BATCHES.remove(batch)) {
                removedBytes += batch.data.length;
            }
        }
        if (removedBytes > 0) {
            PACKED_BATCHES_BYTES.addAndGet(-removedBytes);
        }
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
