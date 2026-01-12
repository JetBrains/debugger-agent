package com.intellij.rt.debugger.agent;

import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

public class OverheadDetectorOverhead {
    private static void measureOverhead(boolean throttlingEnabled) {
        OverheadDetector detector = new OverheadDetector(20);
        detector.throttleWhenOverhead = throttlingEnabled;

        long start = System.nanoTime();
        int repeats = 1_000_000_000;
        for (int i = 0; i < repeats; i++) {
            detector.runIfNoOverhead(new Runnable() {
                @Override
                public void run() {
                    Blackhole.consumeCPU(1);
                }
            });
        }
        long end = System.nanoTime();
        long nanosPerCall = Math.round(1.0 * (end - start) / repeats);
        long totalTimeMs = TimeUnit.NANOSECONDS.toMillis(end - start);
        System.out.println("Single runIfNoOverhead takes " + nanosPerCall + " nanoseconds. " +
                "Throttling enabled: " + throttlingEnabled + ". " +
                "Total time: " + totalTimeMs + " ms. ");
    }


    public static void main(String[] args) {
        // with throttling enabled:  2 ns
        measureOverhead(true);
        // with throttling disabled: 4 ns
        measureOverhead(false);
    }
}
