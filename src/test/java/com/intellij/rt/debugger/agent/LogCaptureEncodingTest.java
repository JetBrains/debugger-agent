package com.intellij.rt.debugger.agent;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class LogCaptureEncodingTest {

    @Test
    public void test() throws Exception {
        String message = "Hello, world!";
        Throwable throwable = getThrowableWithDeepStack(100);

        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        List<StackTraceElement> stack = Arrays.asList(throwable.getStackTrace());
        byte[] encodedBytes = LogCaptureStorage.encodeMessageAndStacks(bytes, 0, bytes.length, stack, null);
        System.out.println(encodedBytes.length); // 11654
        Assert.assertTrue(encodedBytes.length > 11_000);
        Assert.assertTrue(encodedBytes.length < 12_000);
    }

    private Throwable getThrowableWithDeepStack(int depth) {
        if (depth <= 0) {
            return new Throwable();
        }
        return getThrowableWithDeepStack(depth - 1);
    }
}
