package com.intellij.rt.debugger.agent;

import java.util.Random;

public class OverheadTestUtils {
    public static final long[] PRECISIONS = new long[]{15_600_000, 100_000};
    public static final long[] INVOCATION_TIME_NS = new long[]{1_000_000, 9_700, 2300};
    static final double TARGET_OVERHEAD = CaptureStorage.DEFAULT_OVERHEAD_PERCENT / 100;
    static final double MAX_DETECTED_FACTOR = 1.15; // more than 57% should be detected
    static final double MIN_DETECTED_FACTOR = 0.85; // less than 42% should not be detected
    static final double THROTTLING_FACTOR = 1.6; // should be able to throttle up to 80%

    // Time simulation works better with random noise
    private static final double RANDOMNESS = 0.12;

    static ThreadLocal<OverheadDetector.OverheadTracker> wrap(final OverheadDetector detector) {
        return new ThreadLocal<OverheadDetector.OverheadTracker>() {
            @Override
            protected OverheadDetector.OverheadTracker initialValue() {
                return detector.createOverheadTracker();
            }
        };
    }

    static ExperimentInfo runExperiment(final ExperimentConfig config, double measuredPayloadPercent) {
        final long tokens = config.singleInvocationNs;
        double multiplier = 100 / measuredPayloadPercent - 1;

        int repeats = config.repeats;
        final int[] calls = new int[1];
        final Random random = new Random(42);

        for (int i = 0; i < repeats; i++) {
            final double randomMultiplier = random.nextDouble() * RANDOMNESS - RANDOMNESS / 2;
            final double adjustedTokens = tokens * (1 + randomMultiplier);
            config.overheadTracker.get().runIfNoOverhead(new Runnable() {
                @Override
                public void run() {
                    calls[0]++;
                    config.timer.advance(Math.round(adjustedTokens));
                }
            });
            config.timer.advance(Math.round(adjustedTokens * multiplier));
        }

        ExperimentInfo experimentInfo = new ExperimentInfo();
        experimentInfo.measuredInvocations = calls[0];
        experimentInfo.skippedInvocations = repeats - calls[0];
        experimentInfo.nonMeasuredInvocations = (int) (repeats * multiplier);
        return experimentInfo;
    }

    static class ExperimentConfig {
        final MockTimer timer;
        final OverheadDetector detector;
        final ThreadLocal<OverheadDetector.OverheadTracker> overheadTracker;
        final long singleInvocationNs;
        final int repeats;

        private ExperimentConfig(OverheadDetector detector, long timerPrecisionNs, long singleInvocationNs, int repeats) {
            this.singleInvocationNs = singleInvocationNs;
            this.timer = new MockTimer(timerPrecisionNs);
            this.repeats = repeats;
            detector.setTimer(timer);
            this.detector = detector;
            this.overheadTracker = wrap(detector);
        }

        static ExperimentConfig create(double targetOverheadPercent, boolean throttleWhenOverhead,
                                       long timerPrecisionNs, long singleInvocationNs, int repeats) {
            OverheadDetector detector = new OverheadDetector(targetOverheadPercent);
            detector.throttleWhenOverhead = throttleWhenOverhead;
            return new ExperimentConfig(detector, timerPrecisionNs, singleInvocationNs, repeats);
        }
    }

    private static class MockTimer implements OverheadDetector.Timer {
        private final long precisionNs;

        private MockTimer(long precisionNs) {
            this.precisionNs = precisionNs;
        }

        private static class State {
            private long timeNs = 0;
        }

        private final ThreadLocal<State> state = new ThreadLocal<State>() {
            @Override
            protected State initialValue() {
                return new State();
            }
        };

        @Override
        public long nanoTime() {
            State currentState = state.get();
            return (currentState.timeNs / precisionNs) * precisionNs;
        }

        public void advance(long deltaNs) {
            if (deltaNs < 0) {
                throw new IllegalArgumentException("Negative deltaNs: " + deltaNs);
            }
            state.get().timeNs += deltaNs;
        }
    }

    static class ExperimentInfo {
        int measuredInvocations;
        int nonMeasuredInvocations;
        int skippedInvocations;
    }
}
