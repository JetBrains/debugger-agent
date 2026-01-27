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
    private final double myTargetOverheadPercent;

    public OverheadDetector(double targetOverheadPercent) {
        MAX_OVERHEAD_NS = Math.round(targetOverheadPercent * PERIOD_NS / 100);
        myTargetOverheadPercent = targetOverheadPercent;
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

    interface Timer {
        long nanoTime();
    }

    /**
     * This timer is used to decrease the number of calls to System.nanoTime().
     * It updates the time every precisionNs nanoseconds.
     */
    private static class CoarseTimer implements Timer {
        private final AtomicBoolean myInitialized = new AtomicBoolean(false);
        private final long myPrecisionNs;
        private volatile long myTimeNs;

        CoarseTimer(long precisionNs) {
            myPrecisionNs = precisionNs;
        }

        public void initialize() {
            if (!myInitialized.compareAndSet(false, true)) return;
            myTimeNs = System.nanoTime();
            Thread thread = new Thread(new Runnable() {

                @Override
                public void run() {
                    while (true) {
                        LockSupport.parkNanos(myPrecisionNs);
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

    private Timer ourTimer = new CoarseTimer(TimeUnit.MICROSECONDS.toNanos(100));

    // For testing purposes only
    void setTimer(Timer timer) {
        ourTimer = timer;
    }

    interface OverheadTracker {
        void runIfNoOverhead(Runnable runnable);
    }

    private static final OverheadTracker NO_OP_TRACKER = new OverheadTracker() {
        @Override
        public void runIfNoOverhead(Runnable runnable) {
            runnable.run();
        }
    };

    OverheadTracker createOverheadTracker() {
        if (myTargetOverheadPercent >= 100) return NO_OP_TRACKER;
        if (ourTimer instanceof CoarseTimer) {
            ((CoarseTimer) ourTimer).initialize();
        }
        return new PerThread();
    }

    private class PerThread implements OverheadTracker {
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
            if (passedTime >= PERIOD_NS) {
                myOverhead = 0;
            } else {
                long restored = passedTime * MAX_OVERHEAD_NS / PERIOD_NS;
                long overhead = myOverhead - restored;
                if (overhead < 0) {
                    overhead = 0;
                }
                myOverhead = overhead;
            }
        }
    }
}
