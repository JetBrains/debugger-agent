package com.intellij.rt.debugger.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
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
    static final String BATCHING_BUFFER_SIZE_PROPERTY = "logCaptureBatchingBufferSize";
    static final String FORCE_BATCHING_BUFFER_SIZE_PROPERTY = "logCaptureForceBatchingBufferSize";
    private static final int ESTIMATED_THROWABLE_BYTES = 3000;

    private static boolean BATCHING_ENABLED;
    private static long BUFFER_SIZE;
    private static boolean STDOUT_CAPTURE_ENABLED;

    // The debugger reads this value via evaluation to learn how many events have been created by the agent.
    // The agent owns all writes.
    static final AtomicLong EVENT_COUNTER = new AtomicLong();

    /**
     * The debugger writes the greatest event id that was received and decoded successfully via the {@link #packBatchedData()} call.
     * The confirmation is done outside the method itself because it can be called multiple times (see javadoc).
     * The confirmation is not guaranteed, it is used only as an optimization against repeated sending of the same events.
     * <p>
     * The agent checks the flushed state based on this field and {@link #LAST_FLUSHED_EVENT_ID}.
     */
    static volatile long CONFIRMED_FLUSHED_EVENT_ID = -1;

    // It contains raw events that are waiting to be packed.
    // New ones could be added concurrently.
    // Raw or packed data can be flushed concurrently, leading to sending the same events multiple times.
    // It's ok and is handled by the debugger using IDs.
    static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();
    static final AtomicLong EVENTS_PAYLOAD_BYTES = new AtomicLong();
    static final ConcurrentLinkedQueue<PackedBatch> PACKED_BATCHES = new ConcurrentLinkedQueue<>();
    static final AtomicLong PACKED_BATCHES_BYTES = new AtomicLong();

    static final AtomicLong LAST_FLUSHED_EVENT_ID = new AtomicLong(-1);
    static final AtomicLong LAST_PACKED_EVENT_ID = new AtomicLong(-1);
    static final AtomicLong LAST_LOGGING_BREAKPOINT_EVENT_ID = new AtomicLong(-1);

    private interface MemoryFootprintEstimate {
        int memoryFootprintEstimate();

        boolean markRemoved();
    }

    static class Event implements MemoryFootprintEstimate {
        public static final byte STD_OUTPUT_TYPE = 0;
        public static final byte LOGGING_BREAKPOINT_TYPE = 1;

        public final long id;
        public final byte type;
        public final byte[] payload;
        public final Throwable throwable;
        public final CaptureStorage.CapturedStack stack;
        private final AtomicBoolean removed = new AtomicBoolean();

        public Event(long id, byte type, byte[] payload, Throwable throwable, CaptureStorage.CapturedStack stack) {
            this.id = id;
            this.type = type;
            this.payload = payload;
            this.throwable = throwable;
            this.stack = stack;
        }

        @Override
        public int memoryFootprintEstimate() {
            return payload.length;
        }

        @Override
        public boolean markRemoved() {
            return removed.compareAndSet(false, true);
        }

        @Override
        public int hashCode() {
            // This override is not necessary for correctness, but it is a bit faster than Object.hashCode.
            return (int) (id ^ (id >>> 32));
        }

        @Override
        public final boolean equals(Object o) {
            if (!(o instanceof Event)) return false;

            Event event = (Event) o;
            return id == event.id;
        }
    }

    static class PackedBatch implements MemoryFootprintEstimate {
        public final byte[] data;
        public final long lastEventId;
        private final AtomicBoolean removed = new AtomicBoolean();

        public PackedBatch(byte[] data, long lastEventId) {
            this.data = data;
            this.lastEventId = lastEventId;
        }

        @Override
        public int memoryFootprintEstimate() {
            return data.length;
        }

        @Override
        public boolean markRemoved() {
            return removed.compareAndSet(false, true);
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
        BUFFER_SIZE = getBufferSize(properties);
        if (BATCHING_ENABLED && !batchingSchedulerStarted) {
            batchingSchedulerStarted = true;

            final Runnable flushAction = new Runnable() {
                @Override
                public void run() {
                    CAPTURING.set(true);
                    try {
                        flushBatchedData(true);
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

    static long getBufferSize(Properties properties) {
        String forcedBufferSize = properties.getProperty(FORCE_BATCHING_BUFFER_SIZE_PROPERTY);
        if (forcedBufferSize != null) {
            return Long.parseLong(forcedBufferSize);
        }
        String stringMaxSize = properties.getProperty(BATCHING_BUFFER_SIZE_PROPERTY);
        long maxBufferSize = stringMaxSize == null
                ? 5 * 1024L * 1024L // 5MB
                : Long.parseLong(stringMaxSize);
        long runtimeMax = Runtime.getRuntime().maxMemory() / 100; // 1% of max heap
        return Math.min(maxBufferSize, runtimeMax);
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
                flushBatchedData(true);
            }
            if (!STDOUT_CAPTURE_ENABLED) return;

            boolean isErr = fd == FD_ERR;
            long id = createNextEventId(Event.STD_OUTPUT_TYPE);
            ByteArrayOutputStream bas = new ByteArrayOutputStream(); // no need to close it
            try (DataOutputStream dos = new DataOutputStream(bas)) {
                dos.writeInt(len);
                dos.write(bytes, off, len);
                dos.writeBoolean(isErr);
            }
            byte[] payload = bas.toByteArray();
            captureEvent(new Event(id, Event.STD_OUTPUT_TYPE, payload, ThrowableInterner.createThrowable(), CaptureStorage.getCurrentCapturedStack()));
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
            EVENTS_PAYLOAD_BYTES.addAndGet(event.memoryFootprintEstimate());
            flushBatchedData(false);
        } else {
            PackedBatch batch = new PackedBatch(packBytes(Collections.singletonList(event)), -1);
            ThrowableInterner.clear();
            String packed = packBatches(Collections.singletonList(batch));
            outputWritten(packed);
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
        clearConfirmedBatches();
        packRawEvents();
        List<PackedBatch> packedBatchesSnapshot = new ArrayList<>(PACKED_BATCHES);
        if (packedBatchesSnapshot.isEmpty()) return null;
        return packBatches(packedBatchesSnapshot);
    }

    private static long currentEventsEstimatedBytes() {
        long throwablesCount = ThrowableInterner.size();
        if (throwablesCount == 0) {
            // Interner transformation likely did not work, possible in tests or on transformer failures/
            // Switch to considering every throwable to be unique.
            throwablesCount = EVENT_COUNTER.get() - 1 - LAST_PACKED_EVENT_ID.get();
        }
        return EVENTS_PAYLOAD_BYTES.get() + ESTIMATED_THROWABLE_BYTES * throwablesCount;
    }

    private static void packRawEvents() throws IOException {
        if (EVENTS.isEmpty()) return;
        List<Event> eventsSnapshot = new ArrayList<>(EVENTS);
        if (eventsSnapshot.isEmpty()) return;
        enqueuePackedBatch(eventsSnapshot);
        long removedBytes = removeItems(EVENTS, eventsSnapshot);
        EVENTS_PAYLOAD_BYTES.addAndGet(-removedBytes);
        ThrowableInterner.clear();
    }

    private static void enqueuePackedBatch(List<Event> events) throws IOException {
        if (events.isEmpty()) return;
        byte[] packed = packBytes(events);
        long lastPackedId = findMaxId(events);
        PackedBatch batch = new PackedBatch(packed, lastPackedId);
        PACKED_BATCHES.add(batch);
        PACKED_BATCHES_BYTES.addAndGet(batch.memoryFootprintEstimate());
        setIfGreater(LAST_PACKED_EVENT_ID, lastPackedId);
    }

    private static void flushBatchedData(boolean forceOutput) throws IOException {
        clearConfirmedBatches();
        if (forceOutput) {
            packRawEvents();
        } else {
            long eventsBytes = currentEventsEstimatedBytes();
            long packedBytes = PACKED_BATCHES_BYTES.get();
            if (eventsBytes + packedBytes <= BUFFER_SIZE) {
                return;
            }
            packRawEvents();
            packedBytes = PACKED_BATCHES_BYTES.get();
            // We will zip too often if there is little space for raw events.
            if (packedBytes <= BUFFER_SIZE * 9 / 10) {
                return;
            }
        }
        List<PackedBatch> packedBatchesSnapshot = new ArrayList<>(PACKED_BATCHES);
        if (packedBatchesSnapshot.isEmpty()) return;

        outputWritten(packBatches(packedBatchesSnapshot));
        markBatchesFlushed(packedBatchesSnapshot);
    }

    private static void markBatchesFlushed(Collection<PackedBatch> batches) {
        long removedBytes = removeItems(PACKED_BATCHES, batches);
        PACKED_BATCHES_BYTES.addAndGet(-removedBytes);
        setIfGreater(LAST_FLUSHED_EVENT_ID, findMaxPackedEventId(batches));
    }

    private static void clearConfirmedBatches() {
        long confirmedId = CONFIRMED_FLUSHED_EVENT_ID;
        if (confirmedId <= LAST_FLUSHED_EVENT_ID.get()) return;
        Set<PackedBatch> alreadyConfirmed = new HashSet<>();
        for (PackedBatch batch : PACKED_BATCHES) {
            if (batch.lastEventId <= confirmedId) {
                alreadyConfirmed.add(batch);
            }
        }
        markBatchesFlushed(alreadyConfirmed);
    }

    private static byte[] packBytes(List<Event> events) throws IOException {
        assert !events.isEmpty();

        ByteArrayOutputStream bas = new ByteArrayOutputStream(); // no need to close it
        try (GZIPOutputStream gos = new FastGzipOutputStream(bas);
             DataOutputStream dos = new DataOutputStream(gos)) {
            CapturedStackDeduplicator.StackDictionary stackDictionary =
                    CapturedStackDeduplicator.createStackDictionary(events, MAX_STACK_DEPTH);

            dos.writeInt(stackDictionary.stacks.size());
            for (List<StackTraceElement> stack : stackDictionary.stacks) {
                byte[] bytes = packStack(stack);
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }

            dos.writeInt(events.size());
            for (int i = 0; i < events.size(); i++) {
                Event event = events.get(i);
                dos.writeLong(event.id);
                dos.writeByte(event.type);
                dos.writeInt(stackDictionary.throwableStackIds[i]);
                dos.writeInt(stackDictionary.capturedStackIds[i]);
                byte[] bytes = event.payload;
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
        }
        // ensure to close the gzip stream before extracting compressed data.
        return bas.toByteArray();
    }

    private static class FastGzipOutputStream extends GZIPOutputStream {
        FastGzipOutputStream(ByteArrayOutputStream out) throws IOException {
            super(out);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }

    private static byte[] packStack(List<StackTraceElement> stackTrace) throws IOException {
        ByteArrayOutputStream bas = new ByteArrayOutputStream(); // no need to close it
        try (DataOutputStream dos = new DataOutputStream(bas)) {
            CaptureStorage.writeAsyncStackTraceToStream(stackTrace, dos);
        }
        return bas.toByteArray();
    }

    private static String packBatches(Collection<PackedBatch> packedBatches) throws IOException {
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
            }
            byte[] payload = bas.toByteArray();
            captureEvent(new Event(id, Event.LOGGING_BREAKPOINT_TYPE, payload, ThrowableInterner.createThrowable(), CaptureStorage.getCurrentCapturedStack()));
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

    private static <T extends MemoryFootprintEstimate> long removeItems(ConcurrentLinkedQueue<T> queue, Collection<T> items) {
        Set<T> itemsToRemove = items instanceof Set ? (Set<T>) items : new HashSet<>(items);

        long removedBytes = 0;
        for (Iterator<T> queueIterator = queue.iterator(); queueIterator.hasNext(); ) {
            T queueItem = queueIterator.next();
            if (itemsToRemove.contains(queueItem)) {
                queueIterator.remove();
                if (queueItem.markRemoved()) {
                    removedBytes += queueItem.memoryFootprintEstimate();
                }
            }
        }
        return removedBytes;
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
