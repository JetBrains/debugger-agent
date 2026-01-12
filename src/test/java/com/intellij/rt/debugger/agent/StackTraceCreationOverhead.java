package com.intellij.rt.debugger.agent;

public class StackTraceCreationOverhead {

    private static long recursiveFunc(int depth, int repeats) {
        if (depth > 0) return recursiveFunc(depth - 1, repeats);
        long startNs = System.nanoTime();
        for (int i = 0; i < repeats; i++) {
            //noinspection ThrowableNotThrown
            new Throwable("example").getStackTrace();
        }
        return System.nanoTime() - startNs;
    }

    public static void main(String[] args) {
        int repeats = 1_000_000;
        long totalNs = recursiveFunc(20, repeats);
        double exceptionTimeNs = (double) totalNs / repeats;
        // 2760 ns
        System.out.println("Exception creation overhead: " + Math.round(exceptionTimeNs) + " ns");
    }
}
