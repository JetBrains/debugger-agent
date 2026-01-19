package com.intellij.rt.debugger.agent;

public class StackTraceCreationOverhead {

    private static long recursiveFunc(int depth, int repeats) {
        if (depth > 0) return recursiveFunc(depth - 1, repeats);
        long startNs = System.nanoTime();
        int agg = 0;
        for (int i = 0; i < repeats; i++) {
            Throwable throwable = new Throwable();
            int code = System.identityHashCode(throwable);
            agg = agg ^ code;
        }
        long timeNs = System.nanoTime() - startNs;
        System.out.println(agg);
        return timeNs;
    }

    public static void main(String[] args) {
        int repeats = 1_000_000;
        long totalNs = recursiveFunc(100, repeats);
        double exceptionTimeNs = (double) totalNs / repeats;
        // 2449 ns
        System.out.println("Exception creation overhead: " + Math.round(exceptionTimeNs) + " ns");
    }
}
