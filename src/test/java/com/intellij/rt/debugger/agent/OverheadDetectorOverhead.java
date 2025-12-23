package com.intellij.rt.debugger.agent;

public class OverheadDetectorOverhead {
    public static void main(String[] args) {
        OverheadDetector detector = new OverheadDetector();
        long start = System.nanoTime();
        int repeats = 100_000_000;
        for (int i = 0; i < repeats; i++) {
            detector.runIfNoOverhead(new Runnable() {
                @Override
                public void run() {
                }
            });
        }
        long end = System.nanoTime();
        long nanosPerCall = (end - start) / repeats;
        // with throttling enabled:   2 ns
        // with throttling disabled: 11 ns
        System.out.println("Single runIfNoOverhead takes " + nanosPerCall + " nanoseconds");
    }
}
