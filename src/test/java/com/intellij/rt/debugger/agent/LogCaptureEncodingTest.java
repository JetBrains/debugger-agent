package com.intellij.rt.debugger.agent;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

public class LogCaptureEncodingTest {
    private static final String LARGE_BUFFER_SIZE = String.valueOf(5 * 1024 * 1024);
    private static final ThreadLocal<List<List<StackTraceElement>>> STACK_DICTIONARY = new ThreadLocal<>();

    private final Properties properties = new Properties();

    @Before
    public void setUp() {
        assertEquals(
                "Please disable the agent if you try to debug this test. " +
                        "Otherwise, you debug the bundled agent and not the code in the project.",
                this.getClass().getClassLoader(), LogCaptureStorage.class.getClassLoader());
        resetLogCaptureStorage();
        properties.put(LogCaptureStorage.BATCHING_ENABLED_PROPERTY, "true");
        properties.put(LogCaptureStorage.BATCHING_FLUSH_PERIOD_PROPERTY, "999999999"); // never
        properties.put(LogCaptureStorage.BATCHING_BUFFER_SIZE_PROPERTY, LARGE_BUFFER_SIZE);
        LogCaptureStorage.outputWrittenDumpForTests = new ArrayList<>();

    }

    @Test
    public void batchesCapturedStdoutEvents() throws Exception {
        LogCaptureStorage.init(properties, true);

        capture(new FileDescriptor() /* some non-standard FD */, "xxx\n");
        assertEquals("not captured at all", 0, LogCaptureStorage.outputWrittenDumpForTests.size());

        capture(FileDescriptor.out, "aaa\n");
        assertEquals("no flush yet", 0, LogCaptureStorage.outputWrittenDumpForTests.size());

        capture(FileDescriptor.err, "bbb\n");
        assertEquals("buffered data stays in memory below the buffer threshold", 0, LogCaptureStorage.outputWrittenDumpForTests.size());
        assertEquals(0, LogCaptureStorage.PACKED_BATCHES.size());

        try (DataInputStream is = openPackedBatch(LogCaptureStorage.packBatchedData())) {
            assertEquals(2, is.readInt()); // count
            readAndCheckStdoutEvent(0, false, "aaa\n", is);
            readAndCheckStdoutEvent(1, true, "bbb\n", is);
        }
    }

    @Test
    public void batchesLoggingBreakpointEvents() throws Exception {
        LogCaptureStorage.init(properties, false);

        LogCaptureStorage.loggingBreakpointHit(11, "first message");
        assertEquals(0, LogCaptureStorage.outputWrittenDumpForTests.size());
        LogCaptureStorage.loggingBreakpointHit(22, "second message");
        assertEquals(0, LogCaptureStorage.outputWrittenDumpForTests.size());
        assertEquals(0, LogCaptureStorage.PACKED_BATCHES.size());

        try (DataInputStream is = openPackedBatch(LogCaptureStorage.packBatchedData())) {
            assertEquals(2, is.readInt()); // count
            readAndCheckLoggingBreakpointEvent(0, 11, "first message", is);
            readAndCheckLoggingBreakpointEvent(1, 22, "second message", is);
        }
    }

    @Test
    public void stdoutCaptureFlushesPendingLoggingBreakpointEventsFirst() throws Exception {
        LogCaptureStorage.init(properties, true);

        LogCaptureStorage.loggingBreakpointHit(33, "before stdout");
        assertEquals(0, LogCaptureStorage.outputWrittenDumpForTests.size());

        capture(FileDescriptor.out, "stdout\n");

        assertEquals(1, LogCaptureStorage.outputWrittenDumpForTests.size());
        try (DataInputStream is = openDump(0)) {
            assertEquals(1, is.readInt()); // count
            readAndCheckLoggingBreakpointEvent(0, 33, "before stdout", is);
        }
    }

    @Test
    public void stdoutFlushHookWorksWhenStdoutCaptureIsDisabled() throws Exception {
        LogCaptureStorage.init(properties, false);

        LogCaptureStorage.loggingBreakpointHit(44, "before ignored stdout");
        capture(FileDescriptor.out, "stdout\n");

        assertEquals(1, LogCaptureStorage.outputWrittenDumpForTests.size());
        try (DataInputStream is = openDump(0)) {
            assertEquals(1, is.readInt()); // count
            readAndCheckLoggingBreakpointEvent(0, 44, "before ignored stdout", is);
        }
    }

    @Test
    public void keepsEventIdsAndOrderingAcrossMultipleFlushes() throws Exception {
        LogCaptureStorage.init(properties, true);

        LogCaptureStorage.loggingBreakpointHit(55, "first log");
        capture(FileDescriptor.out, "first stdout\n");
        assertEquals(1, LogCaptureStorage.outputWrittenDumpForTests.size());

        LogCaptureStorage.loggingBreakpointHit(66, "second log");
        capture(FileDescriptor.out, "second stdout\n");
        assertEquals(2, LogCaptureStorage.outputWrittenDumpForTests.size());

        try (DataInputStream is = openDump(0)) {
            assertEquals(1, is.readInt()); // count
            readAndCheckLoggingBreakpointEvent(0, 55, "first log", is);
        }
        try (DataInputStream is = openDump(1)) {
            assertEquals(2, is.readInt()); // count
            readAndCheckStdoutEvent(1, false, "first stdout\n", is);
            readAndCheckLoggingBreakpointEvent(2, 66, "second log", is);
        }
    }

    @Test
    public void packBatchedDataReturnsNullWhenNoEventsPending() throws Exception {
        LogCaptureStorage.init(properties, true);

        assertNull(LogCaptureStorage.packBatchedData());
    }

    @Test
    public void packBatchedDataEncodesEventsWithoutSendingThem() throws Exception {
        LogCaptureStorage.init(properties, true);

        LogCaptureStorage.loggingBreakpointHit(77, "first log");
        LogCaptureStorage.loggingBreakpointHit(88, "second log");

        // Sanity: nothing has been auto-flushed yet (limit is high).
        assertEquals(0, LogCaptureStorage.outputWrittenDumpForTests.size());

        String packed = LogCaptureStorage.packBatchedData();
        assertNotNull(packed);

        // The packed string decodes to the events we added, in id order.
        try (DataInputStream is = openPackedBatch(packed)) {
            assertEquals(2, is.readInt());
            readAndCheckLoggingBreakpointEvent(0, 77, "first log", is);
            readAndCheckLoggingBreakpointEvent(1, 88, "second log", is);
        }

        assertEquals("raw events are packed for future debugger reads", 0, LogCaptureStorage.EVENTS.size());
        assertEquals("packed data stays queued", 1, LogCaptureStorage.PACKED_BATCHES.size());
        assertEquals(-1, LogCaptureStorage.LAST_FLUSHED_EVENT_ID.get());

        // outputWritten() was not invoked — pack only returns, never sends.
        assertEquals(0, LogCaptureStorage.outputWrittenDumpForTests.size());

        // Calling pack again yields the same encoded content.
        String packed2 = LogCaptureStorage.packBatchedData();
        assertNotNull(packed2);
        try (DataInputStream is = openPackedBatch(packed2)) {
            assertEquals(2, is.readInt());
            readAndCheckLoggingBreakpointEvent(0, 77, "first log", is);
            readAndCheckLoggingBreakpointEvent(1, 88, "second log", is);
        }

        // A subsequent real flush still drains the same events normally.
        capture(FileDescriptor.out, "trigger\n");
        // capture flushes pending logging-breakpoint events first.
        assertEquals(1, LogCaptureStorage.outputWrittenDumpForTests.size());
        try (DataInputStream is = openDump(0)) {
            assertEquals(2, is.readInt());
            readAndCheckLoggingBreakpointEvent(0, 77, "first log", is);
            readAndCheckLoggingBreakpointEvent(1, 88, "second log", is);
        }
    }

    @Test
    public void rawEventsStayQueuedBelowBufferThreshold() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_BUFFER_SIZE_PROPERTY, LARGE_BUFFER_SIZE);
        LogCaptureStorage.init(properties, true);

        capture(FileDescriptor.out, "first stdout\n");
        capture(FileDescriptor.err, "second stdout\n");

        assertEquals("buffered data stays in memory below the buffer threshold", 0, LogCaptureStorage.outputWrittenDumpForTests.size());
        assertEquals(2, LogCaptureStorage.EVENTS.size());
        assertEquals(0, LogCaptureStorage.PACKED_BATCHES.size());
        assertEquals(0, LogCaptureStorage.PACKED_BATCHES_BYTES.get());
        assertEquals("not sent to debugger yet", -1, LogCaptureStorage.LAST_FLUSHED_EVENT_ID.get());

        String packed = LogCaptureStorage.packBatchedData();
        assertNotNull(packed);
        try (DataInputStream is = openPackedBatch(packed)) {
            assertEquals(2, is.readInt());
            readAndCheckStdoutEvent(0, false, "first stdout\n", is);
            readAndCheckStdoutEvent(1, true, "second stdout\n", is);
        }

        assertEquals("packBatchedData does not drain packed batches", 1, LogCaptureStorage.PACKED_BATCHES.size());
        assertEquals(0, LogCaptureStorage.outputWrittenDumpForTests.size());
    }

    @Test
    public void exceededRawEventBufferThresholdPacksEvents() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_BUFFER_SIZE_PROPERTY, "1");
        LogCaptureStorage.init(properties, true);

        capture(FileDescriptor.out, "a\n");

        assertEquals("raw event bytes are drained after exceeding the estimated byte limit", 0, LogCaptureStorage.EVENTS.size());
        assertEquals(0, LogCaptureStorage.EVENTS_PAYLOAD_BYTES.get());
        assertEquals(0, LogCaptureStorage.PACKED_BATCHES.size());
        assertEquals("packed batches use the same buffer threshold and are sent immediately", 1, LogCaptureStorage.outputWrittenDumpForTests.size());
    }

    @Test
    public void packBatchedDataPacksPendingRawEventsBeforeReturning() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_BUFFER_SIZE_PROPERTY, LARGE_BUFFER_SIZE);
        LogCaptureStorage.init(properties, true);

        capture(FileDescriptor.out, "first stdout\n");
        capture(FileDescriptor.err, "second stdout\n");
        assertEquals(0, LogCaptureStorage.PACKED_BATCHES.size());
        assertEquals(2, LogCaptureStorage.EVENTS.size());

        capture(FileDescriptor.out, "third stdout\n");
        assertEquals(0, LogCaptureStorage.PACKED_BATCHES.size());
        assertEquals(3, LogCaptureStorage.EVENTS.size());

        String packed = LogCaptureStorage.packBatchedData();
        assertNotNull(packed);
        try (DataInputStream batches = openPackedBatches(packed)) {
            assertEquals(1, batches.readInt());
            try (DataInputStream is = openNextPackedBatch(batches)) {
                assertEquals(3, is.readInt());
                readAndCheckStdoutEvent(0, false, "first stdout\n", is);
                readAndCheckStdoutEvent(1, true, "second stdout\n", is);
                readAndCheckStdoutEvent(2, false, "third stdout\n", is);
            }
        }

        assertEquals(0, LogCaptureStorage.EVENTS.size());
        assertEquals(1, LogCaptureStorage.PACKED_BATCHES.size());
    }

    @Test
    public void packBatchedDataDeclaresNumberOfPackedBatches() throws Exception {
        LogCaptureStorage.init(properties, true);

        capture(FileDescriptor.out, "first batch\n");
        assertNotNull(LogCaptureStorage.packBatchedData());
        capture(FileDescriptor.err, "second batch\n");

        String packed = LogCaptureStorage.packBatchedData();
        assertNotNull(packed);
        try (DataInputStream batches = openPackedBatches(packed)) {
            assertEquals(2, batches.readInt());
            try (DataInputStream firstBatch = openNextPackedBatch(batches);
                 DataInputStream secondBatch = openNextPackedBatch(batches)) {
                assertEquals(1, firstBatch.readInt());
                readAndCheckStdoutEvent(0, false, "first batch\n", firstBatch);

                assertEquals(1, secondBatch.readInt());
                readAndCheckStdoutEvent(1, true, "second batch\n", secondBatch);
            }
        }
    }

    @Test
    public void packedBatchOverflowFlushesPendingRawEventsToo() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_BUFFER_SIZE_PROPERTY, LARGE_BUFFER_SIZE);
        LogCaptureStorage.init(properties, true);

        capture(FileDescriptor.out, "first stdout\n");
        capture(FileDescriptor.err, "second stdout\n");
        assertEquals(0, LogCaptureStorage.PACKED_BATCHES.size());
        assertEquals(0, LogCaptureStorage.outputWrittenDumpForTests.size());

        properties.put(LogCaptureStorage.BATCHING_BUFFER_SIZE_PROPERTY, "1");
        LogCaptureStorage.init(properties, true);
        capture(FileDescriptor.out, "third stdout\n");

        assertEquals(1, LogCaptureStorage.outputWrittenDumpForTests.size());
        assertEquals(0, LogCaptureStorage.EVENTS.size());
        assertEquals(0, LogCaptureStorage.PACKED_BATCHES.size());
        try (DataInputStream batches = openDumpBatches(0)) {
            assertEquals(1, batches.readInt());
            try (DataInputStream is = openNextPackedBatch(batches)) {
                assertEquals(3, is.readInt());
                readAndCheckStdoutEvent(0, false, "first stdout\n", is);
                readAndCheckStdoutEvent(1, true, "second stdout\n", is);
                readAndCheckStdoutEvent(2, false, "third stdout\n", is);
            }
        }
    }

    @Test
    public void packedBatchDeduplicatesThrowableStacksByIdentity() throws Exception {
        LogCaptureStorage.init(properties, true);

        Throwable throwable = new Throwable();
        LogCaptureStorage.EVENTS.add(new LogCaptureStorage.Event(
                0, LogCaptureStorage.Event.STD_OUTPUT_TYPE, stdoutPayload("same stack\n", false), throwable, null));
        LogCaptureStorage.EVENTS.add(new LogCaptureStorage.Event(
                1, LogCaptureStorage.Event.STD_OUTPUT_TYPE, stdoutPayload("same stack\n", false), throwable, null));

        String packed = LogCaptureStorage.packBatchedData();
        assertNotNull(packed);
        try (DataInputStream is = openPackedBatch(packed)) {
            assertEquals("same throwable should use one stack dictionary entry", 1, STACK_DICTIONARY.get().size());
            assertEquals(2, is.readInt());
            List<StackTraceElement> firstStack = readAndCheckStdoutEvent(0, false, "same stack\n", is);
            List<StackTraceElement> secondStack = readAndCheckStdoutEvent(1, false, "same stack\n", is);
            assertEquals(firstStack, secondStack);
        }
    }

    @Test
    public void packedBatchUsesSharedDictionaryForThrowableAndCapturedStacks() throws Exception {
        LogCaptureStorage.init(properties, true);

        CaptureStorage.CapturedStack capturedStack = capturedStack(
                new StackTraceElement("Captured", "shared", null, 42));
        LogCaptureStorage.EVENTS.add(new LogCaptureStorage.Event(
                0, LogCaptureStorage.Event.STD_OUTPUT_TYPE, stdoutPayload("first\n", false), new Throwable(), capturedStack));
        LogCaptureStorage.EVENTS.add(new LogCaptureStorage.Event(
                1, LogCaptureStorage.Event.STD_OUTPUT_TYPE, stdoutPayload("second\n", false), new Throwable(), capturedStack));

        String packed = LogCaptureStorage.packBatchedData();
        assertNotNull(packed);
        try (DataInputStream is = openPackedBatch(packed)) {
            assertEquals(3, STACK_DICTIONARY.get().size());
            assertEquals(2, is.readInt());

            assertEquals(0, is.readLong());
            assertEquals(LogCaptureStorage.Event.STD_OUTPUT_TYPE, is.readByte());
            assertEquals(0, is.readInt());
            assertEquals("captured stack id shares the same dictionary namespace", 2, is.readInt());
            readAndCheckStdoutMessage("first\n", false, new DataInputStream(new ByteArrayInputStream(readBytesWithSize(is))));

            assertEquals(1, is.readLong());
            assertEquals(LogCaptureStorage.Event.STD_OUTPUT_TYPE, is.readByte());
            assertEquals(1, is.readInt());
            assertEquals("same captured stack should reuse the shared dictionary id", 2, is.readInt());
            readAndCheckStdoutMessage("second\n", false, new DataInputStream(new ByteArrayInputStream(readBytesWithSize(is))));
        }
    }

    @Test
    public void packedBatchUsesMaxRequiredDepthForDeduplicatedCapturedStack() throws Exception {
        LogCaptureStorage.init(properties, true);

        StackTraceElement capturedFrame = new StackTraceElement("Captured", "shared", null, 42);
        CaptureStorage.CapturedStack capturedStack = capturedStack(capturedFrame);
        LogCaptureStorage.EVENTS.add(new LogCaptureStorage.Event(
                0, LogCaptureStorage.Event.STD_OUTPUT_TYPE, stdoutPayload("full\n", false), throwableWithStackDepth(100), capturedStack));
        LogCaptureStorage.EVENTS.add(new LogCaptureStorage.Event(
                1, LogCaptureStorage.Event.STD_OUTPUT_TYPE, stdoutPayload("room\n", false), throwableWithStackDepth(99), capturedStack));

        String packed = LogCaptureStorage.packBatchedData();
        assertNotNull(packed);
        try (DataInputStream is = openPackedBatch(packed)) {
            assertEquals(3, STACK_DICTIONARY.get().size());
            List<StackTraceElement> capturedDictionaryStack = STACK_DICTIONARY.get().get(2);
            assertEquals("captured stack should use the maximum required depth", 1, capturedDictionaryStack.size());
            assertEquals(capturedFrame, capturedDictionaryStack.get(0));

            assertEquals(2, is.readInt());
            assertEquals(0, is.readLong());
            assertEquals(LogCaptureStorage.Event.STD_OUTPUT_TYPE, is.readByte());
            assertEquals(0, is.readInt());
            assertEquals(2, is.readInt());
            readAndCheckStdoutMessage("full\n", false, new DataInputStream(new ByteArrayInputStream(readBytesWithSize(is))));

            assertEquals(1, is.readLong());
            assertEquals(LogCaptureStorage.Event.STD_OUTPUT_TYPE, is.readByte());
            assertEquals(1, is.readInt());
            assertEquals("same captured stack should reuse id with max required depth", 2, is.readInt());
            readAndCheckStdoutMessage("room\n", false, new DataInputStream(new ByteArrayInputStream(readBytesWithSize(is))));
        }
    }

    static DataInputStream openPackedBatch(String packed) throws IOException {
        try (DataInputStream batches = openPackedBatches(packed)) {
            assertEquals(1, batches.readInt());
            return openNextPackedBatch(batches);
        }
    }

    static DataInputStream openPackedBatches(String packed) {
        return new DataInputStream(new ByteArrayInputStream(packed.getBytes(StandardCharsets.ISO_8859_1)));
    }

    static DataInputStream openNextPackedBatch(DataInputStream batches) throws IOException {
        DataInputStream batch = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(readBytesWithSize(batches))));
        int stackCount = batch.readInt();
        List<List<StackTraceElement>> stacks = new ArrayList<>();
        for (int i = 0; i < stackCount; i++) {
            try (DataInputStream stackStream = new DataInputStream(new ByteArrayInputStream(readBytesWithSize(batch)))) {
                stacks.add(readStackFrames(stackStream));
            }
        }
        STACK_DICTIONARY.set(stacks);
        return batch;
    }

    static DataInputStream openDump(int index) throws IOException {
        return openPackedBatch(LogCaptureStorage.outputWrittenDumpForTests.get(index));
    }

    static DataInputStream openDumpBatches(int index) {
        return openPackedBatches(LogCaptureStorage.outputWrittenDumpForTests.get(index));
    }

    static List<StackTraceElement> readAndCheckStdoutEvent(int expectedId,
                                                           boolean expectedIsErr,
                                                           String expectedMsg,
                                                           DataInputStream is) throws IOException {
        assertEquals(expectedId, is.readLong());
        assertEquals(LogCaptureStorage.Event.STD_OUTPUT_TYPE, is.readByte());
        int throwableId = is.readInt();
        int capturedStackId = is.readInt();
        try (DataInputStream eis = new DataInputStream(new ByteArrayInputStream(readBytesWithSize(is)))) {
            readAndCheckStdoutMessage(expectedMsg, expectedIsErr, eis);
            return stackByIds(throwableId, capturedStackId);
        }
    }

    private static List<StackTraceElement> readAndCheckLoggingBreakpointEvent(int expectedId,
                                                                              int expectedInstrumentationId,
                                                                              String expectedMsg,
                                                                              DataInputStream is) throws IOException {
        assertEquals(expectedId, is.readLong());
        assertEquals(LogCaptureStorage.Event.LOGGING_BREAKPOINT_TYPE, is.readByte());
        int throwableId = is.readInt();
        int capturedStackId = is.readInt();
        try (DataInputStream eis = new DataInputStream(new ByteArrayInputStream(readBytesWithSize(is)))) {
            assertEquals(expectedInstrumentationId, eis.readInt());
            readAndCheckMessage(expectedMsg, eis);
            return stackByIds(throwableId, capturedStackId);
        }
    }

    static List<StackTraceElement> readAndCheckMessageAndStack(String expectedMsg, DataInputStream is) throws IOException {
        readAndCheckMessage(expectedMsg, is);
        return readStackFrames(is);
    }

    private static void readAndCheckMessage(String expectedMsg, DataInputStream is) throws IOException {
        byte[] msgBytes = readBytesWithSize(is);
        String msg = new String(msgBytes, StandardCharsets.UTF_8);
        assertEquals(expectedMsg, msg);
    }

    static List<StackTraceElement> readAndCheckStdoutMessageAndStack(String expectedMsg,
                                                                     boolean expectedIsErr,
                                                                     DataInputStream is) throws IOException {
        readAndCheckStdoutMessage(expectedMsg, expectedIsErr, is);
        return readStackFrames(is);
    }

    private static void readAndCheckStdoutMessage(String expectedMsg,
                                                  boolean expectedIsErr,
                                                  DataInputStream is) throws IOException {
        byte[] msgBytes = readBytesWithSize(is);
        String msg = new String(msgBytes, StandardCharsets.UTF_8);
        assertEquals(expectedMsg, msg);
        assertEquals(expectedIsErr, is.readBoolean());
    }

    private static List<StackTraceElement> stackByIds(int throwableId, int capturedStackId) {
        List<List<StackTraceElement>> stacks = STACK_DICTIONARY.get();
        assertNotNull("expected stack dictionary", stacks);
        assertTrue("throwable stack id is out of dictionary bounds: " + throwableId,
                throwableId >= 0 && throwableId < stacks.size());

        ArrayList<StackTraceElement> stack = new ArrayList<>(stacks.get(throwableId));
        if (capturedStackId >= 0) {
            assertTrue("captured stack id is out of dictionary bounds: " + capturedStackId,
                    capturedStackId < stacks.size());
            stack.addAll(stacks.get(capturedStackId));
        }
        return stack;
    }

    private static List<StackTraceElement> readStackFrames(DataInputStream is) throws IOException {
        assertTrue("expected encoded stack trace after message", is.available() > 0);
        ArrayList<StackTraceElement> stack = new ArrayList<>();
        while (is.available() > 0) {
            boolean regularFrame = is.readBoolean();
            if (regularFrame) {
                stack.add(new StackTraceElement(is.readUTF(), is.readUTF(), null, is.readInt()));
            } else {
                stack.add(null);
            }
        }
        return stack;
    }

    static byte[] readBytesWithSize(DataInputStream is) throws IOException {
        // Performance is not critical, just do it in a loop missing Java 11 readNBytes().
        int size = is.readInt();
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = is.readByte();
        }
        return bytes;
    }

    private static void capture(FileDescriptor fd, String text) {
        LogCaptureStorage.capture(fd, text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] stdoutPayload(String text, boolean isErr) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bas = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bas)) {
            dos.writeInt(bytes.length);
            dos.write(bytes);
            dos.writeBoolean(isErr);
        }
        return bas.toByteArray();
    }

    private static CaptureStorage.CapturedStack capturedStack(final StackTraceElement element) {
        return new CaptureStorage.CapturedStack() {
            @Override
            List<StackTraceElement> getStackTrace() {
                ArrayList<StackTraceElement> stack = new ArrayList<>();
                stack.add(element);
                return stack;
            }
        };
    }

    private static Throwable throwableWithStackDepth(int depth) {
        StackTraceElement[] stackTrace = new StackTraceElement[depth];
        for (int i = 0; i < depth; i++) {
            stackTrace[i] = new StackTraceElement("Regular", "frame" + i, null, i);
        }
        Throwable throwable = new Throwable();
        throwable.setStackTrace(stackTrace);
        return throwable;
    }

    static void resetLogCaptureStorage() {
        LogCaptureStorage.resetStateForTests();
        ThrowableInterner.clear();
        STACK_DICTIONARY.remove();
    }
}
