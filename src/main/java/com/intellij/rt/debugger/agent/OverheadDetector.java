package com.intellij.rt.debugger.agent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * This class is responsible for detecting and managing system overhead while executing tasks.
 * It monitors and tracks task execution times and determines if the system is under significant overhead.
 * It prevents tasks from executing if overhead thresholds are surpassed and throttling is enabled.
 * <p>
 * This algorithm employs a leaky bucket mechanism over a sliding period (PERIOD_NS) to precisely manage overhead,
 * proportionally restoring budget based on elapsed time for gradual decay and rapid recovery from bursts.
 * It caps overhead accumulation at 2x the max budget to handle one-time spikes without prolonged throttling,
 * while a coarse timer minimizes timing costs.
 * Per-thread isolation ensures fair tracking in multithreaded environments.
 */
// DO NOT CHANGE SIGNATURE: used from debugger
public class OverheadDetector {
    // DO NOT CHANGE SIGNATURE: set from debugger
    volatile boolean throttleWhenOverhead = false;
    private final AtomicBoolean myFirstOverheadDetected = new AtomicBoolean(false);

    // Approximately 537ms.
    // Time resolution is ~15.6ms on Windows
    // => period should be at least 15.6 / targetOverheadPercent
    // with targetOverheadPercent = 20%, the period should be at least 78ms
    private static final long PERIOD_NS = 1L << 29;

    /**
     * During a single period ({@link #PERIOD_NS}) of time, the overhead is limited to MAX_OVERHEAD_NS.
     */
    private final long MAX_OVERHEAD_NS;

    public OverheadDetector(double targetOverheadPercent) {
        MAX_OVERHEAD_NS = Math.round(targetOverheadPercent * PERIOD_NS / 100);
    }

    void onOverheadDetected() {
        if (!myFirstOverheadDetected.compareAndSet(false, true)) return;
        if (CaptureStorage.DEBUG) {
            System.out.println("Overhead detected");
        }
        overheadDetected(this);
    }

    // DO NOT CHANGE SIGNATURE: debugger sets a breakpoint here
    @SuppressWarnings("unused")
    private void overheadDetected(OverheadDetector overheadDetector) {
        // IDEA should install a breakpoint here and set throttleWhenOverhead field
        // or disable agent completely
    }

    /**
     * This timer is used to decrease the number of calls to System.nanoTime().
     * It updates the time every precisionNs nanoseconds.
     */
    private static class CoarseTimer {
        private volatile long myTimeNs = System.nanoTime();

        CoarseTimer(final long precisionNs) {
            Thread thread = new Thread(new Runnable() {

                @Override
                public void run() {
                    while (true) {
                        LockSupport.parkNanos(precisionNs);
                        myTimeNs = System.nanoTime();
                    }
                }
            }, "CoarseTimer");
            thread.setDaemon(true);
            thread.start();
        }

        public long nanoTime() {
            return myTimeNs;
        }
    }

    // While set to be 10 microseconds, it could be much more on different systems:
    // 50-60 us on linux
    // 10-50 us on macOS
    // 1000+ us on Windows
    private static final CoarseTimer ourTimer = new CoarseTimer(TimeUnit.MICROSECONDS.toNanos(10));

    class PerThread {
        private long myLastExecutionTime = ourTimer.nanoTime();
        private long myOverhead = 0;
        private boolean myInProgress = false;
        private boolean myLocalThrottleWhenOverhead = throttleWhenOverhead;
        private boolean myLocalFirstOverheadDetected = myFirstOverheadDetected.get();

        /**
         * Runs the provided runnable if there is no overhead detected or throttling is disabled.
         * The execution of the runnable is not guaranteed.
         */
        public void runIfNoOverhead(Runnable runnable) {
            // do nothing in recursive calls
            if (myInProgress) {
                runnable.run();
                return;
            }

            long startTime = ourTimer.nanoTime();
            restore(startTime);

            if (overheadDetected()) {
                notifyOverheadDetected();
                if (isThrottlingEnabled()) {
                    return;
                }
            }

            myInProgress = true;
            try {
                runnable.run();
            } finally {
                long endTime = ourTimer.nanoTime();
                long elapsedTime = endTime - startTime;
                // limit maximum to avoid one-time spikes that hard to restore from
                // can overflow a little, but restores in 2 full periods
                myOverhead = Math.min(2 * MAX_OVERHEAD_NS, myOverhead + elapsedTime);
                myInProgress = false;
            }
        }

        private boolean overheadDetected() {
            return myOverhead >= MAX_OVERHEAD_NS;
        }

        private void notifyOverheadDetected() {
            // notify only for the first time
            if (myLocalFirstOverheadDetected) return;
            myLocalFirstOverheadDetected = true;
            onOverheadDetected();
        }

        private boolean isThrottlingEnabled() {
            if (myLocalThrottleWhenOverhead) return true;

            boolean enabled = throttleWhenOverhead;
            if (enabled) {
                myLocalThrottleWhenOverhead = true;
            }
            return enabled;
        }

        /**
         * Restores the permitted overhead capacity proportionally to the time passed since the last execution.
         */
        private void restore(long currentTime) {
            long lastTime = myLastExecutionTime;
            myLastExecutionTime = currentTime;

            long passedTime = currentTime - lastTime;
            long restored = passedTime * MAX_OVERHEAD_NS / PERIOD_NS;

            long overhead = myOverhead - restored;
            if (overhead < 0) {
                overhead = 0;
            }
            myOverhead = overhead;
        }
    }
}
