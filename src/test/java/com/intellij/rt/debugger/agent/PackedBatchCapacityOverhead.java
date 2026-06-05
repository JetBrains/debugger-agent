package com.intellij.rt.debugger.agent;

import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

//Packed batches: 99
//Packed bytes: 6481311
//Packed bytes per batch: 65468
//Packed batches fitting 5 MiB storage limit: 80
//Capture + pack time: 40185 ns
public class PackedBatchCapacityOverhead {
    private static final long PACKED_BYTES_LIMIT = 5L * 1024L * 1024L;

    private static long recursiveFunc(int depth, int repeats, byte[] message) {
        if (depth > 0) return recursiveFunc(depth - 1, repeats, message);

        long startNs = System.nanoTime();
        for (int i = 0; i < repeats; i++) {
            LogCaptureStorage.capture(FileDescriptor.out, message);
        }
        return System.nanoTime() - startNs;
    }

    public static void main(String[] args) throws Exception {
        int repeats = 100_000;
        int stackDepth = 100;
        byte[] message = "stdout message\n".getBytes(StandardCharsets.UTF_8);

        Properties properties = new Properties();
        properties.put(LogCaptureStorage.BATCHING_ENABLED_PROPERTY, "true");
        properties.put(LogCaptureStorage.BATCHING_FLUSH_PERIOD_PROPERTY, "999999999");
        properties.put(LogCaptureStorage.BATCHING_MAX_EVENTS_PROPERTY, "1000");
        properties.put(LogCaptureStorage.BATCHING_MAX_PACKED_BYTES_PROPERTY, String.valueOf(Long.MAX_VALUE));
        LogCaptureStorage.init(properties, true);

        long totalNs = recursiveFunc(stackDepth, repeats, message);
        long packedBatches = LogCaptureStorage.PACKED_BATCHES.size();
        long packedBytes = LogCaptureStorage.PACKED_BATCHES_BYTES.get();
        double packedBytesPerBatch = (double) packedBytes / packedBatches;
        double captureTimeNs = (double) totalNs / repeats;

        System.out.println("Packed batches: " + packedBatches);
        System.out.println("Packed bytes: " + packedBytes);
        System.out.println("Packed bytes per batch: " + Math.round(packedBytesPerBatch));
        System.out.println("Packed batches fitting 5 MiB storage limit: " + (long) (PACKED_BYTES_LIMIT / packedBytesPerBatch));
        System.out.println("Capture + pack time: " + Math.round(captureTimeNs) + " ns");
    }
}
