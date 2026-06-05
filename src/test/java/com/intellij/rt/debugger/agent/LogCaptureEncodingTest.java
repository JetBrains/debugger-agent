package com.intellij.rt.debugger.agent;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

public class LogCaptureEncodingTest {
    private static final String LARGE_PACKED_BYTE_LIMIT = String.valueOf(5 * 1024 * 1024);

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
        properties.put(LogCaptureStorage.BATCHING_MAX_PACKED_BYTES_PROPERTY, "0");
        LogCaptureStorage.outputWrittenDumpForTests = new ArrayList<>();

    }

    @Test
    public void batchesCapturedStdoutEvents() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "1"); // 1 is ok, 2 is a signal to flush
        LogCaptureStorage.init(properties, true);

        capture(new FileDescriptor() /* some non-standard FD */, "xxx\n");
        assertEquals("not captured at all", 0, LogCaptureStorage.outputWrittenDumpForTests.size());

        capture(FileDescriptor.out, "aaa\n");
        assertEquals("no flush yet", 0, LogCaptureStorage.outputWrittenDumpForTests.size());

        capture(FileDescriptor.err, "bbb\n");
        assertEquals("flushed", 1, LogCaptureStorage.outputWrittenDumpForTests.size());

        try (DataInputStream is = openDump(0)) {
            assertEquals(2, is.readInt()); // count
            readAndCheckStdoutEvent(0, false, "aaa\n", is);
            readAndCheckStdoutEvent(1, true, "bbb\n", is);
        }
    }

    @Test
    public void batchesLoggingBreakpointEvents() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "1"); // 1 is ok, 2 is a signal to flush
        LogCaptureStorage.init(properties, false);

        LogCaptureStorage.loggingBreakpointHit(11, "first message");
        assertEquals(0, LogCaptureStorage.outputWrittenDumpForTests.size());
        LogCaptureStorage.loggingBreakpointHit(22, "second message");
        assertEquals(1, LogCaptureStorage.outputWrittenDumpForTests.size());

        try (DataInputStream is = openDump(0)) {
            assertEquals(2, is.readInt()); // count
            readAndCheckLoggingBreakpointEvent(0, 11, "first message", is);
            readAndCheckLoggingBreakpointEvent(1, 22, "second message", is);
        }
    }

    @Test
    public void stdoutCaptureFlushesPendingLoggingBreakpointEventsFirst() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "100");
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
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "100");
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
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "1"); // 1 is ok, 2 is a signal to flush
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
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "100");
        LogCaptureStorage.init(properties, true);

        assertNull(LogCaptureStorage.packBatchedData());
    }

    @Test
    public void packBatchedDataEncodesEventsWithoutSendingThem() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "100");
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
    public void exceededRawEventLimitPacksEventsWithoutCallingOutputWrittenBelowPackedMemoryLimit() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "1"); // 1 is ok, 2 is a signal to pack
        properties.put(LogCaptureStorage.BATCHING_MAX_PACKED_BYTES_PROPERTY, LARGE_PACKED_BYTE_LIMIT);
        LogCaptureStorage.init(properties, true);

        capture(FileDescriptor.out, "first stdout\n");
        capture(FileDescriptor.err, "second stdout\n");

        assertEquals("packed data stays in memory below the packed-byte limit", 0, LogCaptureStorage.outputWrittenDumpForTests.size());
        assertEquals("raw events are drained after packing", 0, LogCaptureStorage.EVENTS.size());
        assertEquals(1, LogCaptureStorage.PACKED_BATCHES.size());
        assertTrue(LogCaptureStorage.PACKED_BATCHES_BYTES.get() > 0);
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
    public void packBatchedDataPacksPendingRawEventsBeforeReturning() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "1"); // 1 is ok, 2 is a signal to pack
        properties.put(LogCaptureStorage.BATCHING_MAX_PACKED_BYTES_PROPERTY, LARGE_PACKED_BYTE_LIMIT);
        LogCaptureStorage.init(properties, true);

        capture(FileDescriptor.out, "first stdout\n");
        capture(FileDescriptor.err, "second stdout\n");
        assertEquals(1, LogCaptureStorage.PACKED_BATCHES.size());
        assertEquals(0, LogCaptureStorage.EVENTS.size());

        capture(FileDescriptor.out, "third stdout\n");
        assertEquals(1, LogCaptureStorage.PACKED_BATCHES.size());
        assertEquals(1, LogCaptureStorage.EVENTS.size());

        String packed = LogCaptureStorage.packBatchedData();
        assertNotNull(packed);
        try (DataInputStream batches = openPackedBatches(packed)) {
            assertEquals(2, batches.readInt());
            try (DataInputStream is = openNextPackedBatch(batches);
                 DataInputStream secondBatch = openNextPackedBatch(batches)) {
                assertEquals(2, is.readInt());
                readAndCheckStdoutEvent(0, false, "first stdout\n", is);
                readAndCheckStdoutEvent(1, true, "second stdout\n", is);

                assertEquals(1, secondBatch.readInt());
                readAndCheckStdoutEvent(2, false, "third stdout\n", secondBatch);
            }
        }

        assertEquals(0, LogCaptureStorage.EVENTS.size());
        assertEquals(2, LogCaptureStorage.PACKED_BATCHES.size());
    }

    @Test
    public void packedBatchOverflowFlushesPendingRawEventsToo() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "1"); // 1 is ok, 2 is a signal to pack
        properties.put(LogCaptureStorage.BATCHING_MAX_PACKED_BYTES_PROPERTY, LARGE_PACKED_BYTE_LIMIT);
        LogCaptureStorage.init(properties, true);

        capture(FileDescriptor.out, "first stdout\n");
        capture(FileDescriptor.err, "second stdout\n");
        assertEquals(1, LogCaptureStorage.PACKED_BATCHES.size());
        assertEquals(0, LogCaptureStorage.outputWrittenDumpForTests.size());

        properties.put(LogCaptureStorage.BATCHING_MAX_PACKED_BYTES_PROPERTY, "1");
        LogCaptureStorage.init(properties, true);
        capture(FileDescriptor.out, "third stdout\n");

        assertEquals(1, LogCaptureStorage.outputWrittenDumpForTests.size());
        assertEquals(0, LogCaptureStorage.EVENTS.size());
        assertEquals(0, LogCaptureStorage.PACKED_BATCHES.size());
        try (DataInputStream batches = openDumpBatches(0)) {
            assertEquals(2, batches.readInt());
            try (DataInputStream is = openNextPackedBatch(batches);
                 DataInputStream secondBatch = openNextPackedBatch(batches)) {
                assertEquals(2, is.readInt());
                readAndCheckStdoutEvent(0, false, "first stdout\n", is);
                readAndCheckStdoutEvent(1, true, "second stdout\n", is);

                assertEquals(1, secondBatch.readInt());
                readAndCheckStdoutEvent(2, false, "third stdout\n", secondBatch);
            }
        }
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
        return new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(readBytesWithSize(batches))));
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
        try (DataInputStream eis = new DataInputStream(new ByteArrayInputStream(readBytesWithSize(is)))) {
            return readAndCheckStdoutMessageAndStack(expectedMsg, expectedIsErr, eis);
        }
    }

    private static List<StackTraceElement> readAndCheckLoggingBreakpointEvent(int expectedId,
                                                                              int expectedInstrumentationId,
                                                                              String expectedMsg,
                                                                              DataInputStream is) throws IOException {
        assertEquals(expectedId, is.readLong());
        assertEquals(LogCaptureStorage.Event.LOGGING_BREAKPOINT_TYPE, is.readByte());
        try (DataInputStream eis = new DataInputStream(new ByteArrayInputStream(readBytesWithSize(is)))) {
            assertEquals(expectedInstrumentationId, eis.readInt());
            return readAndCheckMessageAndStack(expectedMsg, eis);
        }
    }

    static List<StackTraceElement> readAndCheckMessageAndStack(String expectedMsg, DataInputStream is) throws IOException {
        byte[] msgBytes = readBytesWithSize(is);
        String msg = new String(msgBytes, StandardCharsets.UTF_8);
        assertEquals(expectedMsg, msg);
        return readStackFrames(is);
    }

    static List<StackTraceElement> readAndCheckStdoutMessageAndStack(String expectedMsg,
                                                                     boolean expectedIsErr,
                                                                     DataInputStream is) throws IOException {
        byte[] msgBytes = readBytesWithSize(is);
        String msg = new String(msgBytes, StandardCharsets.UTF_8);
        assertEquals(expectedMsg, msg);
        assertEquals(expectedIsErr, is.readBoolean());
        return readStackFrames(is);
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

    static void resetLogCaptureStorage() {
        LogCaptureStorage.EVENT_COUNTER.set(0);
        LogCaptureStorage.LAST_FLUSHED_EVENT_ID.set(-1);
        LogCaptureStorage.LAST_PACKED_EVENT_ID.set(-1);
        LogCaptureStorage.LAST_LOGGING_BREAKPOINT_EVENT_ID.set(-1);
        LogCaptureStorage.EVENTS.clear();
        LogCaptureStorage.PACKED_BATCHES.clear();
        LogCaptureStorage.PACKED_BATCHES_BYTES.set(0);
        LogCaptureStorage.outputWrittenDumpForTests = null;
    }
}
