package com.intellij.rt.debugger.agent;


import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openjdk.jmh.infra.Blackhole;


@RunWith(JUnitParamsRunner.class)
public class OverheadDetectorTest {
    private static final double TARGET_OVERHEAD_PERCENT = 1;

    // 1% overhead should not be detected
    private final double NON_DETECTED_PERCENT = 0.8 * TARGET_OVERHEAD_PERCENT;
    // 2% overhead should be detected
    private final double DETECTED_PERCENT = 2 * TARGET_OVERHEAD_PERCENT;


    @Test
    public void testNoOverheadDetected() {
        OverheadDetector detector = new OverheadDetector(TARGET_OVERHEAD_PERCENT);
        detector.throttleWhenOverhead = true;

        int repeats = 5_000;
        int invocations = runExperiment(detector, repeats, NON_DETECTED_PERCENT);
        Assert.assertEquals(repeats, invocations);
    }

    @Test
    public void testOverheadDetected() {
        OverheadDetector detector = new OverheadDetector(TARGET_OVERHEAD_PERCENT);
        detector.throttleWhenOverhead = true;

        int repeats = 5_000;
        int invocations = runExperiment(detector, repeats, DETECTED_PERCENT);
        Assert.assertNotEquals(repeats, invocations);
    }

    @Test
    public void testOverheadDetectedMultipleThreads() throws InterruptedException {
        final OverheadDetector detector = new OverheadDetector(TARGET_OVERHEAD_PERCENT);
        detector.throttleWhenOverhead = true;

        final int repeats = 5_000;
        final int[] invocations = new int[2];
        Thread thread1 = new Thread(new Runnable() {
            @Override
            public void run() {
                invocations[0] = runExperiment(detector, repeats, NON_DETECTED_PERCENT);
            }
        });

        Thread thread2 = new Thread(new Runnable() {
            @Override
            public void run() {
                invocations[1] = runExperiment(detector, repeats, DETECTED_PERCENT);
            }
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();

        Assert.assertEquals(repeats, invocations[0]);
        Assert.assertNotEquals(repeats, invocations[1]);
    }

    @Parameters({
            "0.01, 0.99, 1000000",
            "0.01, 0.75, 1000000",
            "0.01, 0.15, 10000",
            "0.01, 0.05, 10000",
            "0.01, 0.01, 1000",
    })
    @Test
    public void testThrottling(double targetFactor, double measuredFactor, int repeats) {
        OverheadDetector detector = new OverheadDetector(100 * targetFactor);
        detector.throttleWhenOverhead = true;

        ExperimentInfo experimentInfo = runExperimentFullInfo(detector, repeats, 100 * measuredFactor);

        double actualFactorAfterThrottling = (double) experimentInfo.measuredInvocations / (experimentInfo.measuredInvocations + experimentInfo.nonMeasuredInvocations);
        String message = "Measured work is " + actualFactorAfterThrottling * 100 + "%, " +
                "expected to be less then " + targetFactor * 100 + "%";
        System.out.println(message);

        Assert.assertTrue(message, actualFactorAfterThrottling <= targetFactor + 0.005);
    }

    @Test
    public void testNoThrottlingWhenDisabled() {
        OverheadDetector detector = new OverheadDetector(TARGET_OVERHEAD_PERCENT);
        detector.throttleWhenOverhead = false;

        int repeats = 5_000;
        int invocations = runExperiment(detector, repeats, 100);
        Assert.assertEquals(repeats, invocations);
    }

    private static int runExperiment(OverheadDetector detector, int repeats, double measuredPayloadPercent) {
        return runExperimentFullInfo(detector, repeats, measuredPayloadPercent).measuredInvocations;
    }

    private static ExperimentInfo runExperimentFullInfo(OverheadDetector detector, int repeats, double measuredPayloadPercent) {
        // Blackhole.consumeCPU(tokens) corresponds to ~16us on my machine.
        // It requires many more repetitions to work with lower token numbers.
        // E.g., stably works with tokens = 1000 and repeats = 100_000.
        final long tokens = 10_000;
        double multiplier = 100 / measuredPayloadPercent - 1;
        long externalTokens = Math.round(tokens * multiplier);

        long startTime = System.nanoTime();
        final int[] calls = new int[1];
        for (int i = 0; i < repeats; i++) {
            detector.runIfNoOverhead(new Runnable() {
                @Override
                public void run() {
                    calls[0]++;
                    Blackhole.consumeCPU(tokens);
                }
            });
            Blackhole.consumeCPU(externalTokens);
        }
        long endTime = System.nanoTime();
        long totalTime = endTime - startTime;
        long timePerConsume = Math.round(totalTime / (1 + multiplier) / repeats);
        System.out.println("Single consume of " + tokens + " tokens takes " + timePerConsume + " ns.");

        ExperimentInfo experimentInfo = new ExperimentInfo();
        experimentInfo.measuredInvocations = calls[0];
        experimentInfo.nonMeasuredInvocations = (int) (repeats * multiplier);
        return experimentInfo;
    }

    private static class ExperimentInfo {
        int measuredInvocations;
        int nonMeasuredInvocations;
    }
}
