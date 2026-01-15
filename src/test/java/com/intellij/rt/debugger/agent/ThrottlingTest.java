package com.intellij.rt.debugger.agent;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class ThrottlingTest {
    private final double measuredFactor;
    private final long timerPrecisionNs;
    private final long singleInvocationNs;

    public ThrottlingTest(double measuredFactor, long timerPrecisionNs, long singleInvocationNs) {
        this.measuredFactor = measuredFactor;
        this.timerPrecisionNs = timerPrecisionNs;
        this.singleInvocationNs = singleInvocationNs;
    }

    @Parameterized.Parameters(name = "{index}: measuredFactor={0}, timerPrecisionNs={1}, singleInvocationNs={2}")
    public static Collection<Object[]> data() {
        double[] measuredFactors = {0.99, 0.75, 0.5, 0.25, 0.1, 0.05, 0.01};
        long[] precisions = {15_600_000, 50_000, 10_000, 0};
        long[] invocationTimeNs = {16_000, 2500};
        ArrayList<Object[]> results = new ArrayList<>();
        for (double measuredFactor : measuredFactors) {
            for (long precision : precisions) {
                for (long invocationTime : invocationTimeNs) {
                    results.add(new Object[]{measuredFactor, precision, invocationTime});
                }
            }
        }
        return results;
    }

    @Test
    public void testThrottling() {
        double targetFactor = OverheadTestUtils.TARGET_OVERHEAD;
        OverheadTestUtils.ExperimentConfig config = OverheadTestUtils.ExperimentConfig.create(
                100 * targetFactor,
                true,
                timerPrecisionNs,
                singleInvocationNs,
                10_000_000);

        OverheadTestUtils.ExperimentInfo experimentInfo = OverheadTestUtils.runExperiment(config, 100 * measuredFactor);

        double actualFactorAfterThrottling = (double) experimentInfo.measuredInvocations / (experimentInfo.measuredInvocations + experimentInfo.nonMeasuredInvocations);
        String message = "Measured work is " + actualFactorAfterThrottling * 100 + "%, " +
                "expected to be less then " + targetFactor * 100 + "%";
        System.out.println(message);

        if (measuredFactor >= targetFactor * OverheadTestUtils.MIN_DETECTED_FACTOR) {
            if (measuredFactor >= targetFactor * OverheadTestUtils.MAX_DETECTED_FACTOR) {
                Assert.assertNotEquals(0, experimentInfo.skippedInvocations);
            }
            Assert.assertTrue(message, actualFactorAfterThrottling <= OverheadTestUtils.MAX_DETECTED_FACTOR * targetFactor);
        } else {
            Assert.assertEquals(0, experimentInfo.skippedInvocations);
        }
    }
}
