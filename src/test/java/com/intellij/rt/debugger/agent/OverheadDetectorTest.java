package com.intellij.rt.debugger.agent;


import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;


@RunWith(Parameterized.class)
public class OverheadDetectorTest {
    private static final double TARGET_OVERHEAD_PERCENT = OverheadTestUtils.TARGET_OVERHEAD * 100;
    private static final int REPEATS = 1_000_000;
    private final long timerPrecisionNs;
    private final long singleInvocationNs;

    public OverheadDetectorTest(long timerPrecisionNs, long singleInvocationNs) {
        this.timerPrecisionNs = timerPrecisionNs;
        this.singleInvocationNs = singleInvocationNs;
    }

    @Parameterized.Parameters(name = "{index}: timerPrecisionNs={0}, singleInvocationNs={1}")
    public static Collection<Object[]> data() {
        ArrayList<Object[]> results = new ArrayList<>();
        for (long precision : OverheadTestUtils.PRECISIONS) {
            for (long invocationTime : OverheadTestUtils.INVOCATION_TIME_NS) {
                results.add(new Object[]{precision, invocationTime});
            }
        }
        return results;
    }

    @Test
    public void testOverheadDetectedMultipleThreads() throws InterruptedException {
        final OverheadTestUtils.ExperimentConfig config = create(true);

        final OverheadTestUtils.ExperimentInfo[] infos = new OverheadTestUtils.ExperimentInfo[2];
        Thread thread1 = new Thread(new Runnable() {
            @Override
            public void run() {
                infos[0] = OverheadTestUtils.runExperiment(config, OverheadTestUtils.MIN_DETECTED_FACTOR * TARGET_OVERHEAD_PERCENT);
            }
        });

        Thread thread2 = new Thread(new Runnable() {
            @Override
            public void run() {
                infos[1] = OverheadTestUtils.runExperiment(config, OverheadTestUtils.MAX_DETECTED_FACTOR * TARGET_OVERHEAD_PERCENT);
            }
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();

        Assert.assertEquals(0, infos[0].skippedInvocations);
        Assert.assertNotEquals(0, infos[1].skippedInvocations);
    }

    @Test
    public void testNoThrottlingWhenDisabled() {
        OverheadTestUtils.ExperimentConfig config = create(false);

        OverheadTestUtils.ExperimentInfo experimentInfo = OverheadTestUtils.runExperiment(config, 100);
        Assert.assertEquals(0, experimentInfo.skippedInvocations);
    }

    @Test
    public void testNoOverheadDetectedWith100PercentTarget() {
        // tests that we can disable detector by setting target to 100%
        OverheadTestUtils.ExperimentConfig config = OverheadTestUtils.ExperimentConfig.create(100,
                true,
                timerPrecisionNs,
                singleInvocationNs,
                REPEATS);
        OverheadTestUtils.ExperimentInfo experimentInfo = OverheadTestUtils.runExperiment(config, 100);
        Assert.assertEquals(0, experimentInfo.skippedInvocations);
    }

    private OverheadTestUtils.ExperimentConfig create(boolean throttleWhenOverhead) {
        return OverheadTestUtils.ExperimentConfig.create(TARGET_OVERHEAD_PERCENT,
                throttleWhenOverhead,
                timerPrecisionNs,
                singleInvocationNs,
                REPEATS);
    }
}
