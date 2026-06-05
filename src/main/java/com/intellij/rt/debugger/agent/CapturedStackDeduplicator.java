package com.intellij.rt.debugger.agent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

final class CapturedStackDeduplicator {
    static final class StackDictionary {
        final List<List<StackTraceElement>> stacks;
        final int[] throwableStackIds;
        final int[] capturedStackIds;

        private StackDictionary(List<List<StackTraceElement>> stacks, int[] throwableStackIds, int[] capturedStackIds) {
            this.stacks = stacks;
            this.throwableStackIds = throwableStackIds;
            this.capturedStackIds = capturedStackIds;
        }
    }

    private static final class StackRef {
        final int id;
        final List<StackTraceElement> stack;

        private StackRef(int id, List<StackTraceElement> stack) {
            this.id = id;
            this.stack = stack;
        }
    }

    private final List<LogCaptureStorage.Event> events;
    private final int maxStackDepth;
    private final ArrayList<List<StackTraceElement>> stacks;
    private final int[] throwableStackIds;
    private final int[] capturedStackIds;
    private final IdentityHashMap<Throwable, StackRef> throwableStacks;
    private final IdentityHashMap<CaptureStorage.CapturedStack, CapturedStackInfo> capturedStacks;

    private CapturedStackDeduplicator(List<LogCaptureStorage.Event> events, int maxStackDepth) {
        this.events = events;
        this.maxStackDepth = maxStackDepth;
        int eventCount = events.size();
        stacks = new ArrayList<>();
        throwableStackIds = new int[eventCount];
        capturedStackIds = new int[eventCount];
        throwableStacks = new IdentityHashMap<>();
        capturedStacks = new IdentityHashMap<>();
    }

    static StackDictionary createStackDictionary(List<LogCaptureStorage.Event> events, int maxStackDepth) {
        CapturedStackDeduplicator deduplicator = new CapturedStackDeduplicator(events, maxStackDepth);
        deduplicator.collectThrowableStacksAndCapturedStackDepths();
        deduplicator.collectCapturedStacks();
        return new StackDictionary(deduplicator.stacks, deduplicator.throwableStackIds, deduplicator.capturedStackIds);
    }

    private void collectThrowableStacksAndCapturedStackDepths() {
        for (int i = 0; i < events.size(); i++) {
            LogCaptureStorage.Event event = events.get(i);
            StackRef throwableStackRef = getThrowableStackRef(event.throwable);
            throwableStackIds[i] = throwableStackRef.id;

            if (event.stack == null) {
                continue;
            }

            CapturedStackInfo capturedStackInfo = capturedStacks.get(event.stack);
            if (capturedStackInfo == null) {
                capturedStackInfo = new CapturedStackInfo(event.stack);
                capturedStacks.put(event.stack, capturedStackInfo);
            }
            capturedStackInfo.requireDepth(maxStackDepth - throwableStackRef.stack.size());
        }
    }

    private StackRef getThrowableStackRef(Throwable throwable) {
        StackRef stackRef = throwableStacks.get(throwable);
        if (stackRef != null) return stackRef;

        stackRef = addStack(CaptureStorage.getThrowableStackTrace(throwable));
        throwableStacks.put(throwable, stackRef);
        return stackRef;
    }

    private void collectCapturedStacks() {
        for (int i = 0; i < events.size(); i++) {
            CaptureStorage.CapturedStack capturedStack = events.get(i).stack;
            if (capturedStack == null) {
                capturedStackIds[i] = -1;
                continue;
            }

            CapturedStackInfo capturedStackInfo = capturedStacks.get(capturedStack);
            if (capturedStackInfo.stackId == -1) {
                capturedStackInfo.stackId = addStack(capturedStackInfo.getStackTrace()).id;
            }
            capturedStackIds[i] = capturedStackInfo.stackId;
        }
    }

    private StackRef addStack(List<StackTraceElement> stack) {
        StackRef stackRef = new StackRef(stacks.size(), stack);
        stacks.add(stack);
        return stackRef;
    }

    private static final class CapturedStackInfo {
        private final CaptureStorage.CapturedStack capturedStack;
        private int maxRequiredDepth;
        private List<StackTraceElement> stackTrace;
        private int stackId = -1;

        private CapturedStackInfo(CaptureStorage.CapturedStack capturedStack) {
            this.capturedStack = capturedStack;
        }

        private void requireDepth(int depth) {
            if (maxRequiredDepth < depth) {
                maxRequiredDepth = depth;
            }
        }

        private List<StackTraceElement> getStackTrace() {
            if (stackTrace == null) {
                stackTrace = CaptureStorage.getCapturedStackTrace(capturedStack, maxRequiredDepth);
            }
            return stackTrace;
        }
    }
}
