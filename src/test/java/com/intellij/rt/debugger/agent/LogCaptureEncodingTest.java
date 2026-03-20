package com.intellij.rt.debugger.agent;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class LogCaptureEncodingTest {

    @Test
    public void test() throws Exception {
        String message = "Hello, world!";

        Random random = new Random();
        List<String> randomNames = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            randomNames.add(genRandomString(random, 10));
        }

        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        List<StackTraceElement> stack = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            stack.add(genStackTraceElement(random, randomNames));
        }
        byte[] encodedBytes = LogCaptureStorage.encodeMessageAndStacks(bytes, 0, bytes.length, stack, null);
        System.out.println(encodedBytes.length); // 10
        Assert.assertTrue(encodedBytes.length > 0);
        Assert.assertTrue(encodedBytes.length < 100);
    }

    private StackTraceElement genStackTraceElement(Random random, List<String> names) {
        StringBuilder className = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i != 0) {
                className.append('.');
            }
            String part = names.get(random.nextInt(names.size()));
            className.append(part);
        }
        String methodName = genRandomString(random, 10);
        int lineNumber = random.nextInt(1000);
        return new StackTraceElement(className.toString(), methodName, null, lineNumber);
    }

    private String genRandomString(Random random, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }
}
