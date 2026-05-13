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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LogCaptureEncodingTest {
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
        LogCaptureStorage.outputWrittenDumpForTests = new ArrayList<>();

    }

    @Test
    public void batchesCapturedStdoutEvents() throws Exception {
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "1"); // 1 is ok, 2 is a signal to flush
        LogCaptureStorage.init(properties, true);

        LogCaptureStorage.capture(FileDescriptor.out, "aaa\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, LogCaptureStorage.outputWrittenDumpForTests.size());
        LogCaptureStorage.capture(FileDescriptor.out, "bbb\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, LogCaptureStorage.outputWrittenDumpForTests.size());

        try (DataInputStream is = openDump(0)) {
            assertEquals(2, is.readInt()); // count
            readAndCheckStdoutEvent(0, "aaa\n", is);
            readAndCheckStdoutEvent(1, "bbb\n", is);
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

        LogCaptureStorage.capture(FileDescriptor.out, "stdout\n".getBytes(StandardCharsets.UTF_8));

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
        LogCaptureStorage.capture(FileDescriptor.out, "stdout\n".getBytes(StandardCharsets.UTF_8));

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
        LogCaptureStorage.capture(FileDescriptor.out, "first stdout\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, LogCaptureStorage.outputWrittenDumpForTests.size());

        LogCaptureStorage.loggingBreakpointHit(66, "second log");
        LogCaptureStorage.capture(FileDescriptor.out, "second stdout\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, LogCaptureStorage.outputWrittenDumpForTests.size());

        try (DataInputStream is = openDump(0)) {
            assertEquals(1, is.readInt()); // count
            readAndCheckLoggingBreakpointEvent(0, 55, "first log", is);
        }
        try (DataInputStream is = openDump(1)) {
            assertEquals(2, is.readInt()); // count
            readAndCheckStdoutEvent(1, "first stdout\n", is);
            readAndCheckLoggingBreakpointEvent(2, 66, "second log", is);
        }
    }

    static DataInputStream openDump(int index) throws IOException {
        String output = LogCaptureStorage.outputWrittenDumpForTests.get(index);
        return new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(output.getBytes(StandardCharsets.ISO_8859_1))));
    }

    static List<StackTraceElement> readAndCheckStdoutEvent(int expectedId, String expectedMsg, DataInputStream is) throws IOException {
        assertEquals(expectedId, is.readLong());
        assertEquals(LogCaptureStorage.Event.STD_OUTPUT_TYPE, is.readByte());
        try (DataInputStream eis = new DataInputStream(new ByteArrayInputStream(readBytesWithSize(is)))) {
            return readAndCheckMessageAndStack(expectedMsg, eis);
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

    static void resetLogCaptureStorage() {
        LogCaptureStorage.EVENT_COUNTER.set(0);
        LogCaptureStorage.LAST_FLUSHED_EVENT_ID.set(-1);
        LogCaptureStorage.LAST_LOGGING_BREAKPOINT_EVENT_ID.set(-1);
        LogCaptureStorage.EVENTS.clear();
        LogCaptureStorage.outputWrittenDumpForTests = null;
    }
}
