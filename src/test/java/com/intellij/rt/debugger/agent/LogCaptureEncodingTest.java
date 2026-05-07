package com.intellij.rt.debugger.agent;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LogCaptureEncodingTest {

    @Test
    public void test() throws Exception {
        // Please disable the agent if you try to debug this test.
        // Otherwise, you debug the bundled agent and not the code in the project.
        assertEquals(this.getClass().getClassLoader(), LogCaptureStorage.class.getClassLoader());

        Properties properties = new Properties();
        properties.put(LogCaptureStorage.BATCHING_ENABLED_PROPERTY, "true");
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "1"); // 1 is ok, 2 is a signal to flush
        properties.put(LogCaptureStorage.BATCHING_FLUSH_PERIOD_PROPERTY, "999999999"); // never
        LogCaptureStorage.init(properties);
        LogCaptureStorage.outputWrittenDumpForTests = new ArrayList<>();

        capture(new FileDescriptor() /* some non-standard FD */, "xxx\n");
        assertEquals("not captured at all", 0, LogCaptureStorage.outputWrittenDumpForTests.size());

        capture(FileDescriptor.out, "aaa\n");
        assertEquals("no flush yet", 0, LogCaptureStorage.outputWrittenDumpForTests.size());

        capture(FileDescriptor.err, "bbb\n");
        assertEquals("flushed", 1, LogCaptureStorage.outputWrittenDumpForTests.size());

        String output = LogCaptureStorage.outputWrittenDumpForTests.get(0);

        try (DataInputStream is = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(output.getBytes(StandardCharsets.ISO_8859_1))))) {
            assertEquals(2, is.readInt()); // count
            readAndCheckEvent(0, false, "aaa\n", is);
            readAndCheckEvent(1, true, "bbb\n", is);
        }
    }

    private static void readAndCheckEvent(int expectedId, boolean expectedIsErr, String expectedMsg, DataInputStream is) throws IOException {
        assertEquals(expectedId, is.readLong());
        byte[] msgAndStackTrace = readBytesWithSize(is);
        try (DataInputStream eis = new DataInputStream(new ByteArrayInputStream(msgAndStackTrace))) {
            byte[] msgBytes = readBytesWithSize(eis);
            String msg = new String(msgBytes, StandardCharsets.UTF_8);
            assertEquals(expectedMsg, msg);
            boolean isErr = eis.readBoolean();
            assertEquals(expectedIsErr, isErr);
            // don't hassle with stack trace
        }
    }

    private static byte[] readBytesWithSize(DataInputStream is) throws IOException {
        // Performance is not critical, just do it in a loop missing Java 11 readNBytes().
        int size = is.readInt();
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = is.readByte();
        }
        return bytes;
    }

    private static void capture(FileDescriptor fd, String text) {
        LogCaptureStorage.capture(getFileDescriptorId(fd), text.getBytes(StandardCharsets.UTF_8));
    }
    
    private static int getFileDescriptorId(FileDescriptor fd) {
        try {
            java.lang.reflect.Field fdField = FileDescriptor.class.getDeclaredField("fd");
            fdField.setAccessible(true);
            return fdField.getInt(fd);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get file descriptor id", e);
        }
    }
}
