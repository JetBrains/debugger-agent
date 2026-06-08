package com.intellij.rt.debugger.agent;

import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

//Packed batches: 59
//Packed bytes: 1987391
//Packed bytes per batch: 33685
//Packed batches fitting 5 MiB storage limit: 155
//Capture + pack time: 54638 ns
public class PackedBatchCapacityOverhead {
    private static final long PACKED_BYTES_LIMIT = 5L * 1024L * 1024L;

    private static void recursiveCapture(int depth, int repeats, byte[] message) {
        if (depth > 0) {
            recursiveCapture(depth - 1, repeats, message);
            return;
        }

        for (int i = 0; i < repeats; i++) {
            LogCaptureStorage.capture(FileDescriptor.out, message);
        }
    }

    public static void main(String[] args) throws Exception {
        int repeats = 1_000_000;
        int stackDepth = 100;
        byte[] message = "stdout message\n".getBytes(StandardCharsets.UTF_8);

        Properties properties = new Properties();
        properties.put(LogCaptureStorage.BATCHING_ENABLED_PROPERTY, "true");
        properties.put(LogCaptureStorage.BATCHING_FLUSH_PERIOD_PROPERTY, "999999999");
        LogCaptureStorage.init(properties, true);

        long startNs = System.nanoTime();
        recursiveCapture(stackDepth, repeats, message);
        LogCaptureStorage.packBatchedData();
        long totalNs = System.nanoTime() - startNs;
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
