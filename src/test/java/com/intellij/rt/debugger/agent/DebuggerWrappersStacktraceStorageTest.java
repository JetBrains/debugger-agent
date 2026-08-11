package com.intellij.rt.debugger.agent;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.Assert.*;

public class DebuggerWrappersStacktraceStorageTest {
    @Before
    public void setUp() {
        Properties properties = new Properties();
        properties.setProperty("throttling", "false");
        CaptureStorage.init(properties);
        CaptureStorage.clearCurrentStacksForTests();
        CaptureStorage.setEnabled(true);
        CaptureStorage.DEBUG = false;
    }

    @Test
    public void storesSharedFlowStacktraceByIndex() {
        Object sharedFlow = new Object();

        CaptureStorage.captureSharedFlowStacktrace(sharedFlow, 42L);

        assertNotNull(stack(sharedFlow, Long.valueOf(42L)));
        assertNull(stack(sharedFlow, Long.valueOf(43L)));
    }

    @Test
    public void dropSharedFlowStacktraceRemovesEntry() {
        Object sharedFlow = new Object();

        CaptureStorage.captureSharedFlowStacktrace(sharedFlow, 42L);
        assertNotNull(stack(sharedFlow, Long.valueOf(42L)));

        CaptureStorage.dropSharedFlowStacktrace(sharedFlow, 42L);

        assertNull(stack(sharedFlow, Long.valueOf(42L)));
    }

    @Test
    public void matchDoesNotChangeExistingSharedFlowEntry() {
        Object sharedFlow = new Object();

        CaptureStorage.captureSharedFlowStacktrace(sharedFlow, 42L);
        List<StackTraceElement> stackBeforeInsert = stack(sharedFlow, Long.valueOf(42L));

        CaptureStorage.insertEnterSharedFlowStacktrace(sharedFlow, 42L);
        assertEquals(stackBeforeInsert, stack(sharedFlow, Long.valueOf(42L)));
        assertEquals(1, CaptureStorage.getCurrentStackFrameCountForTests());
    }

    @Test
    public void matchMakesStacktraceCurrentForThread() {
        Object sharedFlow = new Object();

        CaptureStorage.captureSharedFlowStacktrace(sharedFlow, 42L);
        assertNull(CaptureStorage.getCurrentCapturedStack());

        CaptureStorage.insertEnterSharedFlowStacktrace(sharedFlow, 42L);
        CaptureStorage.CapturedStack currentStack = CaptureStorage.getCurrentCapturedStack();
        assertNotNull(currentStack);
        assertEquals(stack(sharedFlow, Long.valueOf(42L)), CaptureStorage.getCapturedStackTrace(currentStack, 1000));
        assertNotNull(CaptureStorage.getCapturedStackForThread(1000, Thread.currentThread()));
        assertEquals(1, CaptureStorage.getCurrentStackFrameCountForTests());
    }

    @Test
    public void matchWithoutExistingEntryDoesNotCreateStacktrace() {
        Object sharedFlow = new Object();

        CaptureStorage.insertEnterSharedFlowStacktrace(sharedFlow, 42L);

        assertNull(stack(sharedFlow, Long.valueOf(42L)));
        assertNull(CaptureStorage.getCurrentCapturedStack());
        assertEquals(0, CaptureStorage.getCurrentStackFrameCountForTests());
    }

    @Test
    public void matchWithoutExistingEntryKeepsCurrentInsertStacktrace() {
        Object key = new Object();
        Object sharedFlow = new Object();

        CaptureStorage.capture(key);
        CaptureStorage.insertEnter(key);
        CaptureStorage.CapturedStack currentStack = CaptureStorage.getCurrentCapturedStack();
        try {
            CaptureStorage.insertEnterSharedFlowStacktrace(sharedFlow, 42L);

            assertSame(currentStack, CaptureStorage.getCurrentCapturedStack());
            assertEquals(1, CaptureStorage.getCurrentStackFrameCountForTests());
        }
        finally {
            CaptureStorage.insertExit(key);
        }

        assertNull(CaptureStorage.getCurrentCapturedStack());
    }

    @Test
    public void repeatedMatchesAppendIndexedStacktraces() {
        Object sharedFlow = new Object();

        CaptureStorage.captureSharedFlowStacktrace(sharedFlow, 42L);
        CaptureStorage.CapturedStack firstStack = capturedStack(sharedFlow, Long.valueOf(42L));
        CaptureStorage.captureSharedFlowStacktrace(sharedFlow, 43L);
        CaptureStorage.CapturedStack secondStack = capturedStack(sharedFlow, Long.valueOf(43L));
        assertNotSame(firstStack, secondStack);

        CaptureStorage.insertEnterSharedFlowStacktrace(sharedFlow, 42L);
        assertSame(firstStack, CaptureStorage.getCurrentCapturedStack());

        CaptureStorage.insertEnterSharedFlowStacktrace(sharedFlow, 43L);
        assertSame(secondStack, CaptureStorage.getCurrentCapturedStack());
        assertEquals(2, CaptureStorage.getCurrentStackFrameCountForTests());
    }

    @Test
    public void missingMatchKeepsPreviousIndexedStacktrace() {
        Object sharedFlow = new Object();

        CaptureStorage.captureSharedFlowStacktrace(sharedFlow, 42L);
        CaptureStorage.insertEnterSharedFlowStacktrace(sharedFlow, 42L);
        CaptureStorage.CapturedStack currentStack = CaptureStorage.getCurrentCapturedStack();
        assertNotNull(currentStack);

        CaptureStorage.insertEnterSharedFlowStacktrace(sharedFlow, 43L);

        assertSame(currentStack, CaptureStorage.getCurrentCapturedStack());
        assertEquals(1, CaptureStorage.getCurrentStackFrameCountForTests());
    }

    @Test
    public void regularInsertExitClearsMatchedStackFromSameScope() {
        Object key = new Object();
        Object sharedFlow = new Object();

        CaptureStorage.capture(key);
        CaptureStorage.insertEnter(key);
        try {
            CaptureStorage.captureSharedFlowStacktrace(sharedFlow, 42L);
            CaptureStorage.insertEnterSharedFlowStacktrace(sharedFlow, 42L);
            assertNotNull(CaptureStorage.getCurrentCapturedStack());
            assertEquals(2, CaptureStorage.getCurrentStackFrameCountForTests());
        }
        finally {
            CaptureStorage.insertExit(key);
        }

        assertNull(CaptureStorage.getCurrentCapturedStack());
    }

    @Test
    public void stateFlowSupportsNullStateKey() {
        Object stateFlow = new Object();

        CaptureStorage.captureStateFlowStacktrace(stateFlow, null);
        assertNotNull(stack(stateFlow, null));
        CaptureStorage.insertEnterStateFlowStacktrace(stateFlow, null);
        assertNotNull(CaptureStorage.getCurrentCapturedStack());
        assertEquals(1, CaptureStorage.getCurrentStackFrameCountForTests());
    }

    @Test
    public void dropStateFlowStacktraceSupportsNullStateKey() {
        Object stateFlow = new Object();

        CaptureStorage.captureStateFlowStacktrace(stateFlow, null);
        assertNotNull(stack(stateFlow, null));

        CaptureStorage.dropStateFlowStacktrace(stateFlow, null);

        assertNull(stack(stateFlow, null));
    }

    @Test
    public void stateFlowInsertUsesStateKey() {
        Object stateFlow = new Object();
        Object state = new Object();
        Object anotherState = new Object();

        CaptureStorage.captureStateFlowStacktrace(stateFlow, state);
        CaptureStorage.insertEnterStateFlowStacktrace(stateFlow, state);
        CaptureStorage.CapturedStack currentStack = CaptureStorage.getCurrentCapturedStack();
        assertNotNull(currentStack);
        assertEquals(stack(stateFlow, state), CaptureStorage.getCapturedStackTrace(currentStack, 1000));
        assertNull(stack(stateFlow, anotherState));
        assertEquals(1, CaptureStorage.getCurrentStackFrameCountForTests());
    }

    @Test
    public void channelStorageUsesSegmentAsOwner() {
        Object channel = new Object();
        Object segment = new Object();
        Object anotherSegment = new Object();

        CaptureStorage.captureChannelStacktrace(channel, segment, 7);
        CaptureStorage.insertEnterChannelStacktrace(channel, segment, 7);
        CaptureStorage.CapturedStack currentStack = CaptureStorage.getCurrentCapturedStack();
        assertNotNull(currentStack);
        assertEquals(stack(segment, Integer.valueOf(7)), CaptureStorage.getCapturedStackTrace(currentStack, 1000));
        assertNull(stack(anotherSegment, Integer.valueOf(7)));
        assertEquals(1, CaptureStorage.getCurrentStackFrameCountForTests());
    }

    @Test
    public void channelMatchBeforeCaptureIsCompletedByLaterMatchAfterCapture() {
        Object channel = new Object();
        Object segment = new Object();

        CaptureStorage.insertEnterChannelStacktrace(channel, segment, 7);
        assertNull(CaptureStorage.getCapturedStackForThread(1000, Thread.currentThread()));
        assertEquals(0, CaptureStorage.getCurrentStackFrameCountForTests());

        CaptureStorage.captureChannelStacktrace(channel, segment, 7);
        assertNull(CaptureStorage.getCurrentCapturedStack());

        CaptureStorage.insertEnterChannelStacktrace(channel, segment, 7);

        CaptureStorage.CapturedStack currentStack = CaptureStorage.getCurrentCapturedStack();
        assertNotNull(currentStack);
        assertEquals(stack(segment, Integer.valueOf(7)), CaptureStorage.getCapturedStackTrace(currentStack, 1000));
        assertEquals(1, CaptureStorage.getCurrentStackFrameCountForTests());
    }

    @Test
    public void repeatedSameChannelMatchDoesNotDuplicateIndexedStacktrace() {
        Object channel = new Object();
        Object segment = new Object();

        CaptureStorage.captureChannelStacktrace(channel, segment, 7);
        CaptureStorage.CapturedStack capturedStack = capturedStack(segment, Integer.valueOf(7));

        CaptureStorage.insertEnterChannelStacktrace(channel, segment, 7);
        CaptureStorage.insertEnterChannelStacktrace(channel, segment, 7);

        assertSame(capturedStack, CaptureStorage.getCurrentCapturedStack());
        assertEquals(1, CaptureStorage.getCurrentStackFrameCountForTests());
    }

    @Test
    public void missingChannelMatchKeepsPreviousIndexedStacktrace() {
        Object channel = new Object();
        Object segment = new Object();
        Object anotherSegment = new Object();

        CaptureStorage.captureChannelStacktrace(channel, segment, 7);
        CaptureStorage.insertEnterChannelStacktrace(channel, segment, 7);
        CaptureStorage.CapturedStack currentStack = CaptureStorage.getCurrentCapturedStack();
        assertNotNull(currentStack);

        CaptureStorage.insertEnterChannelStacktrace(channel, anotherSegment, 7);

        assertSame(currentStack, CaptureStorage.getCurrentCapturedStack());
        assertEquals(1, CaptureStorage.getCurrentStackFrameCountForTests());
    }

    private static List<StackTraceElement> stack(Object owner, Object index) {
        return CaptureStorage.getIndexedStackTraceForTests(owner, index, 1000);
    }

    private static CaptureStorage.CapturedStack capturedStack(Object owner, Object index) {
        return CaptureStorage.getIndexedCapturedStackForTests(owner, index);
    }

}
