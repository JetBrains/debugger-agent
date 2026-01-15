package com.intellij.rt.debugger.agent;

import java.util.Random;

public class OverheadTestUtils {
    public static final long[] PRECISIONS = new long[]{15_600_000, 10_000};
    public static final long[] INVOCATION_TIME_NS = new long[]{1_000_000, 10_000, 2500};
    static final double TARGET_OVERHEAD = 0.2;
    static final double MAX_DETECTED_FACTOR = 2.85; // more than 57% should be detected
    static final double MIN_DETECTED_FACTOR = 0.40; // less than 8% should not be detected
    // Time simulation works better with small deterministic random modifications
    // to the measured periods.
    private static final double RANDOMNESS = 0.12;

    static ThreadLocal<OverheadDetector.PerThread> wrap(final OverheadDetector detector) {
        return new ThreadLocal<OverheadDetector.PerThread>() {
            @Override
            protected OverheadDetector.PerThread initialValue() {
                return detector.new PerThread();
            }
        };
    }

    private static double genRandomFactor(Random random) {
        return 1 - RANDOMNESS + (random.nextDouble() * RANDOMNESS * 2);
    }

    static ExperimentInfo runExperiment(final ExperimentConfig config, double measuredPayloadPercent) {
        final Random random = new Random(42);
        final long tokens = config.singleInvocationNs;
        double multiplier = 100 / measuredPayloadPercent - 1;

        int repeats = config.repeats;
        final int[] calls = new int[1];

        for (int i = 0; i < repeats; i++) {
            double randomFactor = genRandomFactor(random);
            final double randomTokens = tokens * randomFactor;
            config.threadLocalDetector.get().runIfNoOverhead(new Runnable() {
                @Override
                public void run() {
                    calls[0]++;
                    config.timer.advance(Math.round(randomTokens));
                }
            });
            config.timer.advance(Math.round(randomTokens * multiplier));
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
        final ThreadLocal<OverheadDetector.PerThread> threadLocalDetector;
        final long singleInvocationNs;
        final int repeats;

        private ExperimentConfig(OverheadDetector detector, long timerPrecisionNs, long singleInvocationNs, int repeats) {
            this.singleInvocationNs = singleInvocationNs;
            this.timer = new MockTimer(timerPrecisionNs);
            this.repeats = repeats;
            detector.setTimer(timer);
            this.detector = detector;
            this.threadLocalDetector = wrap(detector);
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
            private long deferredNs = 0;
        }

        private final ThreadLocal<State> state = new ThreadLocal<State>() {
            @Override
            protected State initialValue() {
                return new State();
            }
        };

        @Override
        public long nanoTime() {
            return state.get().timeNs;
        }

        public void advance(long deltaNs) {
            if (deltaNs < 0) {
                throw new IllegalArgumentException("Negative deltaNs: " + deltaNs);
            }
            State currentState = state.get();
            long deferredNs = currentState.deferredNs + deltaNs;
            if (deferredNs >= precisionNs) {
                currentState.timeNs += deferredNs;
                currentState.deferredNs = 0;
            } else {
                currentState.deferredNs = deferredNs;
            }
        }
    }

    static class ExperimentInfo {
        int measuredInvocations;
        int nonMeasuredInvocations;
        int skippedInvocations;
    }
}
