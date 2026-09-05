// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.rt.debugger.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class CaptureStorage {
  public static final String GENERATED_INSERT_METHOD_POSTFIX = "$$$capture";
  private static final ConcurrentIdentityWeakHashMap<Object, CapturedStack> STORAGE_GENERAL = new ConcurrentIdentityWeakHashMap<>();
  private static final ConcurrentIdentityWeakHashMap<Throwable, CapturedStack> STORAGE_THROWABLES = new ConcurrentIdentityWeakHashMap<>();
  private static final ConcurrentIdentityWeakHashMap<Object, ConcurrentMap<Object, CapturedStack>> STORAGE_INDEXED =
          new ConcurrentIdentityWeakHashMap<>();
  private static final ConcurrentIdentityWeakHashMap<Object, ConcurrentMap<Object, DeferredCapturedStack>> PENDING_INDEXED_MATCHES =
          new ConcurrentIdentityWeakHashMap<>();
  private static final Object NULL_INDEX = new Object();

  private static final ConcurrentIdentityWeakHashMap<Thread, Deque<CurrentStackFrame>> THREAD_TO_STACKS_MAP = new ConcurrentIdentityWeakHashMap<>();

  private static final ThreadLocal<Deque<CurrentStackFrame>> CURRENT_STACKS = new ThreadLocal<Deque<CurrentStackFrame>>() {
    @Override
    protected Deque<CurrentStackFrame> initialValue() {
      return new LinkedList<>();
    }
  };

  private static final boolean storeAsyncStackTracesForAllThreads = Boolean.parseBoolean(
          System.getProperty("debugger.async.stack.trace.for.all.threads", "false")
  );

  static final double DEFAULT_OVERHEAD_PERCENT = 50;
  private static final String PACKAGE_PREFIX = CaptureStorage.class.getPackage().getName();
  private static OverheadDetector ourOverheadDetector = new OverheadDetector(DEFAULT_OVERHEAD_PERCENT, true);

  static void init(Properties properties) {
    String overheadPercent = properties.getProperty("overheadPercent");
    String throttlingValue = properties.getProperty("throttling");

    double overhead = overheadPercent != null ? Double.parseDouble(overheadPercent) : DEFAULT_OVERHEAD_PERCENT;
    boolean throttlingEnabled = throttlingValue == null || Boolean.parseBoolean(throttlingValue);
    ourOverheadDetector = new OverheadDetector(overhead, throttlingEnabled);
  }

  static class ThreadLocalContext {
    final OverheadDetector.OverheadTracker overheadTracker = ourOverheadDetector.createOverheadTracker();
    boolean throwableCaptureDisabled = false;
  }

  static final ThreadLocal<ThreadLocalContext> CURRENT_CONTEXT = new ThreadLocal<ThreadLocalContext>() {
    @Override
    protected ThreadLocalContext initialValue() {
      return new ThreadLocalContext();
    }
  };

  private static Deque<CurrentStackFrame> getStacksForCurrentThread() {
    if (storeAsyncStackTracesForAllThreads) {
      Thread currentThread = Thread.currentThread();
      Deque<CurrentStackFrame> capturedStacks = THREAD_TO_STACKS_MAP.get(currentThread);
      if (capturedStacks == null) {
        capturedStacks = new LinkedList<>();
        THREAD_TO_STACKS_MAP.put(currentThread, capturedStacks);
      }
      return capturedStacks;
    } else {
      return CURRENT_STACKS.get();
    }
  }

  static CapturedStack getCurrentCapturedStack() {
    return peekCurrentStack(getStacksForCurrentThread());
  }

  @SuppressWarnings("StaticNonFinalField")
  public static boolean DEBUG = true; // set from debugger
  private static boolean ENABLED = true; // set from debugger

  static final StackTraceElement ASYNC_STACK_ELEMENT =
          new StackTraceElement("--- Async", "Stack.Trace --- ", "captured by IntelliJ IDEA debugger", -1);
  static final StackTraceElement THROTTLED_STACK_ELEMENT =
          new StackTraceElement("< Unknown", "Stack > ", "was not captured due to throttling", -1);
  private static final int DEBUG_ASYNC_STACK_TRACE_LIMIT = Integer.getInteger("debugger.agent.debug.async.stack.trace.limit", 256);

  //// METHODS CALLED FROM THE USER PROCESS

  @SuppressWarnings("unused")
  public static void capture(final Object key) {
    if (!ENABLED) {
      return;
    }
    captureCurrentStack(new CapturedStackStore() {
      @Override
      public void put(CapturedStack stack) {
        STORAGE_GENERAL.put(key, stack);
      }

      @Override
      public String getDescription() {
        return getKeyText(key);
      }
    }, "captureGeneral");
  }

  @SuppressWarnings("unused")
  public static void captureThrowableBacktrace(Object backtrace) {
    ThrowableInterner.captureBacktrace(backtrace);
  }

  @SuppressWarnings("unused")
  public static void captureThrowable(final Throwable throwable) {
    final ThreadLocalContext context = CURRENT_CONTEXT.get();
    if (!ENABLED || context.throwableCaptureDisabled) {
      return;
    }
    runWithoutThrowableCapture(context, new Runnable() {
      @Override
      public void run() {
        // TODO: support coroutine stack traces
        try {
          if (DEBUG) {
            //System.out.println("captureThrowable " + getCallerDescriptorForLogging() + " - " + getKeyText(throwable));
          }
          CapturedStack stack = getCurrentCapturedStack();
          if (stack != null) {
            // Ensure that we don't leak throwable here, IDEA-360126
            assert !(stack instanceof ExceptionCapturedStack) ||
                    ((ExceptionCapturedStack) stack).myException != throwable;
            STORAGE_THROWABLES.put(throwable, stack);
          }
        }
        // TODO: check whether it's ok to use assertions, and if we should catch Throwable everywhere
        catch (AssertionError | Exception e) {
          handleException(e);
        }
      }
    });
  }

  @SuppressWarnings("unused")
  public static void insertEnter(final Object key) {
    if (!ENABLED) {
      return;
    }
    runWithoutThrowableCapture(CURRENT_CONTEXT.get(), new Runnable() {
      @Override
      public void run() {
        try {
          CapturedStack stack = STORAGE_GENERAL.get(key);
//          logStorageEvent("insertEnter",
//                  "before stack is pushed " + getCallerDescriptorForLogging() + " -> " + getKeyText(key),
//                  stack);
          pushCurrentStack(stack);
//          logStorageEvent("insertEnter",
//                  "after stack is pushed " + getCallerDescriptorForLogging() + " -> " + getKeyText(key));
        }
        catch (Exception e) {
          handleException(e);
        }
      }
    });
  }

  @SuppressWarnings("unused")
  public static void insertExit(final Object key) {
    if (!ENABLED) {
      return;
    }
    runWithoutThrowableCapture(CURRENT_CONTEXT.get(), new Runnable() {
      @Override
      public void run() {
        try {
          // frameworks may modify thread locals to avoid memory leaks, so do not fail if currentStacks is empty
          // check https://youtrack.jetbrains.com/issue/IDEA-357455 for more details
          int currentStackCount = popCurrentStack();
          logStorageEvent("insertExit",
                          getCallerDescriptorForLogging() + " <- " + getKeyText(key) + ", stack removed (" + currentStackCount + ")");
        } catch (Exception e) {
          handleException(e);
        }
      }
    });
  }

  @SuppressWarnings("unused")
  public static void captureSharedFlowStacktrace(final Object sharedFlow, final long index) {
    captureIndexedStack(sharedFlow, Long.valueOf(index));
  }

  @SuppressWarnings("unused")
  public static void dropSharedFlowStacktrace(final Object sharedFlow, final long index) {
    dropIndexedStack(sharedFlow, Long.valueOf(index));
  }

  @SuppressWarnings("unused")
  public static void insertEnterSharedFlowStacktrace(final Object sharedFlow, final long index) {
    insertEnterIndexedStack(sharedFlow, Long.valueOf(index));
  }

  @SuppressWarnings("unused")
  public static void captureStateFlowStacktrace(final Object stateFlow, final Object state) {
    captureIndexedStack(stateFlow, state);
  }

  @SuppressWarnings("unused")
  public static void dropStateFlowStacktrace(final Object stateFlow, final Object state) {
    dropIndexedStack(stateFlow, state);
  }

  @SuppressWarnings("unused")
  public static void insertEnterStateFlowStacktrace(final Object stateFlow, final Object state) {
    insertEnterIndexedStack(stateFlow, state);
  }

  @SuppressWarnings("unused")
  public static void captureChannelStacktrace(final Object channel, final Object segment, final int index) {
    captureChannelIndexedStack(channel, segment, Integer.valueOf(index));
  }

  @SuppressWarnings("unused")
  public static void insertEnterChannelStacktrace(final Object channel, final Object segment, final int index) {
    insertEnterChannelIndexedStack(channel, segment, Integer.valueOf(index));
  }

  private static final ConcurrentIdentityWeakHashMap<ClassLoader, Method> COROUTINE_GET_CALLER_FRAME_METHODS = new ConcurrentIdentityWeakHashMap<>();

  @SuppressWarnings("unused")
  public static Object coroutineOwner(final Object key) {
    if (!ENABLED) {
      return key;
    }
    return withoutThrowableCapture(new Callable<Object>() {
      @Override
      public Object call() {
        try {
          Method getCallerFrameMethod = getGetCallerFrameMethod(key);
          Object res = key;
          while (true) {
            //TODO: slow implementation for now, need to put the code directly into the insert point
            Object caller = getCallerFrameMethod.invoke(res);
            if (caller == null) {
              return res;
            }
            if ("kotlinx.coroutines.debug.internal.DebugProbesImpl$CoroutineOwner".equals(caller.getClass().getName())) {
              return caller;
            }
            res = caller;
          }
        } catch (Exception e) {
          handleException(e);
        }
        return key;
      }
    });
  }

  @SuppressWarnings("unused")
  public static StackTraceElement[] getAsyncStackTrace(final Throwable throwable) {
    if (!ENABLED) {
      return throwable.getStackTrace();
    }
    return withoutThrowableCapture(new Callable<StackTraceElement[]>() {
      @Override
      public StackTraceElement[] call() {
        try {
          CapturedStack stack = STORAGE_THROWABLES.get(throwable);
          if (stack != null) {
            CapturedStack capturedStack = createCapturedStack(throwable, stack);
            ArrayList<StackTraceElement> stackTrace = getStackTrace(capturedStack, CaptureAgent.throwableAsyncStackDepthLimit());
            return stackTrace.toArray(new StackTraceElement[0]);
          }
        } catch (Exception e) {
          handleException(e);
        }
        return throwable.getStackTrace();
      }
    });
  }

  //// END - METHODS CALLED FROM THE USER PROCESS

  private interface Callable<T> {
    T call();
  }

  private interface CapturedStackStore {
    void put(CapturedStack stack);

    String getDescription();
  }

  private static void captureIndexedStack(final Object owner, final Object index) {
    if (!ENABLED || owner == null) {
      return;
    }
    final Object normalizedIndex = normalizeIndex(index);
    captureCurrentStack(new CapturedStackStore() {
      @Override
      public void put(CapturedStack stack) {
        putIndexedStack(owner, normalizedIndex, stack);
      }

      @Override
      public String getDescription() {
        return getIndexedKeyText(owner, normalizedIndex);
      }
    }, "captureIndexed");
  }

  private static void captureChannelIndexedStack(final Object channel, final Object segment, final Object index) {
    if (!ENABLED || segment == null) {
      return;
    }
    final Object normalizedIndex = normalizeIndex(index);
    final DeferredCapturedStack pendingMatch = getPendingIndexedStack(segment, normalizedIndex);
    captureCurrentStack(new CapturedStackStore() {
      @Override
      public void put(CapturedStack stack) {
        putIndexedStack(segment, normalizedIndex, stack);
        completePendingIndexedStack(segment, normalizedIndex, stack);
        bindChannelStackToWaitingReceiver(segment, normalizedIndex, stack);
      }

      @Override
      public String getDescription() {
        return getIndexedKeyText(segment, normalizedIndex) + " in " + getNullableKeyText(channel);
      }
    }, "captureChannelIndexed", pendingMatch);
  }

  private static void captureCurrentStack(final CapturedStackStore store,
                                          final String debugPrefix) {
    captureCurrentStack(store, debugPrefix, null);
  }

  private static void captureCurrentStack(final CapturedStackStore store,
                                          final String debugPrefix,
                                          final CapturedStack ignoredCurrentStack) {
    captureStack(store, debugPrefix, ignoredCurrentStack);
  }

  private static void captureStack(final CapturedStackStore store,
                                   final String debugPrefix,
                                   final CapturedStack ignoredCurrentStack) {
    ThreadLocalContext context = CURRENT_CONTEXT.get();
    boolean executed = runWithOverheadTrackingAndWithoutThrowableCapture(context, new Runnable() {
      @Override
      public void run() {
        try {
          CapturedStack previous = getCurrentCapturedStackExcept(ignoredCurrentStack);
          if (debugPrefix.contains("Indexed")) {
            logStorageEvent(debugPrefix,
                    "previous captured stack before merging" + getCallerDescriptorForLogging() + " - " + store.getDescription() +
                            ", previous current stack: " + getStackIdentity(previous),
                    previous);
          }
          CapturedStack capturedStack = createCapturedStack(new Throwable(), previous);
          store.put(capturedStack);
          if (debugPrefix.contains("Indexed")) {
            logStorageEvent(debugPrefix,
                    "after merging with current captured stack" + getCallerDescriptorForLogging() + " - " + store.getDescription() +
                            ", previous current stack: " + getStackIdentity(previous),
                    capturedStack);
          }
        }
        // TODO: check whether it's ok to use assertions, and if we should catch Throwable everywhere
        catch (AssertionError | Exception e) {
          handleException(e);
        }
      }
    });
    if (executed) return;
    runWithoutThrowableCapture(context, new Runnable() {
      @Override
      public void run() {
        try {
          // skip previously captured stacks to minimize overhead
          store.put(ThrottledCapturedStack.INSTANCE);
          logStorageEvent(debugPrefix + " (throttled)",
                          getCallerDescriptorForLogging() + " - " + store.getDescription(),
                          ThrottledCapturedStack.INSTANCE);
        }
        catch (AssertionError | Exception e) {
          handleException(e);
        }
      }
    });
  }

  private static void dropIndexedStack(final Object owner, final Object index) {
    if (!ENABLED || owner == null) {
      return;
    }
    final Object normalizedIndex = normalizeIndex(index);
    runWithoutThrowableCapture(CURRENT_CONTEXT.get(), new Runnable() {
      @Override
      public void run() {
        try {
          removeIndexedStack(owner, normalizedIndex);
          removePendingIndexedStack(owner, normalizedIndex);
          logStorageEvent("dropIndexedStack",
                          getCallerDescriptorForLogging() + " - " + getIndexedKeyText(owner, normalizedIndex));
        }
        catch (Exception e) {
          handleException(e);
        }
      }
    });
  }

  private static void insertEnterIndexedStack(final Object owner, final Object index) {
    if (!ENABLED || owner == null) {
      return;
    }
    final Object normalizedIndex = normalizeIndex(index);
    runWithoutThrowableCapture(CURRENT_CONTEXT.get(), new Runnable() {
      @Override
      public void run() {
        try {
          CapturedStack stack = getIndexedStack(owner, normalizedIndex);
          logStorageEvent("insertEnterIndexedStack",
                          "before stack is saved " + getCallerDescriptorForLogging() + " -> " +
                          getIndexedKeyText(owner, normalizedIndex),
                          stack);
          int currentStackCount = pushCurrentIndexedStack(stack);
          logStorageEvent("insertEnterIndexedStack",
                          getCallerDescriptorForLogging() + " -> " + getIndexedKeyText(owner, normalizedIndex) +
                          ", stack saved (" + currentStackCount + ")");
        }
        catch (Exception e) {
          handleException(e);
        }
      }
    });
  }

  private static void insertEnterChannelIndexedStack(final Object channel, final Object segment, final Object index) {
    if (!ENABLED || segment == null) {
      return;
    }
    final Object normalizedIndex = normalizeIndex(index);
    runWithoutThrowableCapture(CURRENT_CONTEXT.get(), new Runnable() {
      @Override
      public void run() {
        try {
          CapturedStack stack = getIndexedStack(segment, normalizedIndex);
          if (stack == null) {
            stack = getOrCreatePendingIndexedStack(segment, normalizedIndex);
          }
          logStorageEvent("insertEnterChannelIndexedStack",
                          "before stack is saved " + getCallerDescriptorForLogging() + " -> " +
                          getIndexedKeyText(segment, normalizedIndex) + " in " + getNullableKeyText(channel),
                          stack);
          int currentStackCount = pushCurrentIndexedStack(stack);
          logStorageEvent("insertEnterChannelIndexedStack",
                          getCallerDescriptorForLogging() + " -> " + getIndexedKeyText(segment, normalizedIndex) +
                          " in " + getNullableKeyText(channel) + ", stack saved (" + currentStackCount + ")");
        }
        catch (Exception e) {
          handleException(e);
        }
      }
    });
  }

  private static Object normalizeIndex(Object index) {
    return index == null ? NULL_INDEX : index;
  }

  private static ConcurrentMap<Object, CapturedStack> getOrCreateIndexedStacks(Object owner) {
    ConcurrentMap<Object, CapturedStack> result = STORAGE_INDEXED.get(owner);
    if (result != null) {
      return result;
    }
    ConcurrentMap<Object, CapturedStack> created = new ConcurrentHashMap<>();
    ConcurrentMap<Object, CapturedStack> existing = STORAGE_INDEXED.putIfAbsent(owner, created);
    return existing == null ? created : existing;
  }

  private static void putIndexedStack(Object owner, Object index, CapturedStack stack) {
    getOrCreateIndexedStacks(owner).put(index, stack);
  }

  private static CapturedStack getIndexedStack(Object owner, Object index) {
    ConcurrentMap<Object, CapturedStack> stacks = STORAGE_INDEXED.get(owner);
    return stacks == null ? null : stacks.get(index);
  }

  private static void removeIndexedStack(Object owner, Object index) {
    ConcurrentMap<Object, CapturedStack> stacks = STORAGE_INDEXED.get(owner);
    if (stacks != null) {
      stacks.remove(index);
    }
  }

  private static ConcurrentMap<Object, DeferredCapturedStack> getOrCreatePendingIndexedStacks(Object owner) {
    ConcurrentMap<Object, DeferredCapturedStack> result = PENDING_INDEXED_MATCHES.get(owner);
    if (result != null) {
      return result;
    }
    ConcurrentMap<Object, DeferredCapturedStack> created = new ConcurrentHashMap<>();
    ConcurrentMap<Object, DeferredCapturedStack> existing = PENDING_INDEXED_MATCHES.putIfAbsent(owner, created);
    return existing == null ? created : existing;
  }

  private static DeferredCapturedStack getOrCreatePendingIndexedStack(Object owner, Object index) {
    ConcurrentMap<Object, DeferredCapturedStack> stacks = getOrCreatePendingIndexedStacks(owner);
    DeferredCapturedStack result = stacks.get(index);
    if (result != null) {
      return result;
    }
    DeferredCapturedStack created = new DeferredCapturedStack();
    DeferredCapturedStack existing = stacks.putIfAbsent(index, created);
    return existing == null ? created : existing;
  }

  private static DeferredCapturedStack getPendingIndexedStack(Object owner, Object index) {
    ConcurrentMap<Object, DeferredCapturedStack> stacks = PENDING_INDEXED_MATCHES.get(owner);
    return stacks == null ? null : stacks.get(index);
  }

  private static DeferredCapturedStack removePendingIndexedStack(Object owner, Object index) {
    ConcurrentMap<Object, DeferredCapturedStack> stacks = PENDING_INDEXED_MATCHES.get(owner);
    return stacks == null ? null : stacks.remove(index);
  }

  private static void completePendingIndexedStack(Object owner, Object index, CapturedStack stack) {
    DeferredCapturedStack pending = removePendingIndexedStack(owner, index);
    if (pending != null) {
      pending.setStack(stack);
    }
  }

  private static void bindChannelStackToWaitingReceiver(Object segment, Object index, CapturedStack stack) {
    if (!(index instanceof Integer)) {
      return;
    }
    Object waiter = getChannelCellState(segment, ((Integer)index).intValue());
    Object continuation = getContinuationFromChannelWaiter(waiter);
    if (continuation == null) {
      return;
    }
    Object coroutineOwner = coroutineOwner(continuation);
    STORAGE_GENERAL.put(coroutineOwner, stack);
    logStorageEvent("bindChannelStackToWaitingReceiver",
                    getIndexedKeyText(segment, index) + " -> " + getKeyText(coroutineOwner),
                    stack);
  }

  private static Object getChannelCellState(Object segment, int index) {
    try {
      Method getState = findMethod(segment.getClass(), "getState", int.class);
      if (getState == null) {
        return null;
      }
      getState.setAccessible(true);
      return getState.invoke(segment, Integer.valueOf(index));
    }
    catch (Throwable ignored) {
      return null;
    }
  }

  private static Object getContinuationFromChannelWaiter(Object waiter) {
    Object unwrappedWaiter = unwrapChannelWaiter(waiter);
    if (unwrappedWaiter == null) {
      return null;
    }
    Object iteratorContinuation = asCoroutineStackFrame(getFieldValue(unwrappedWaiter, "continuation"));
    if (iteratorContinuation != null) {
      return iteratorContinuation;
    }
    Object receiveCatchingContinuation = asCoroutineStackFrame(getFieldValue(unwrappedWaiter, "cont"));
    if (receiveCatchingContinuation != null) {
      return receiveCatchingContinuation;
    }
    Object selectContinuation = getContinuationFromSelectWaiter(unwrappedWaiter);
    if (selectContinuation != null) {
      return selectContinuation;
    }
    return asCoroutineStackFrame(unwrappedWaiter);
  }

  private static Object getContinuationFromSelectWaiter(Object waiter) {
    Object state = getFieldValue(waiter, "state");
    Object continuation = asCoroutineStackFrame(state);
    if (continuation != null) {
      return continuation;
    }

    Object stateValue = getAtomicValue(state);
    return asCoroutineStackFrame(stateValue);
  }

  private static Object getAtomicValue(Object atomicValue) {
    if (atomicValue == null) {
      return null;
    }
    Object value = getFieldValue(atomicValue, "value");
    if (value != null) {
      return value;
    }
    try {
      Method getValue = findMethod(atomicValue.getClass(), "getValue");
      if (getValue == null) {
        return null;
      }
      getValue.setAccessible(true);
      return getValue.invoke(atomicValue);
    }
    catch (Throwable ignored) {
      return null;
    }
  }

  private static Object unwrapChannelWaiter(Object waiter) {
    Object result = waiter;
    Object nested;
    while ((nested = getFieldValue(result, "waiter")) != null && nested != result) {
      result = nested;
    }
    return result;
  }

  private static Object getFieldValue(Object owner, String fieldName) {
    if (owner == null) {
      return null;
    }
    try {
      Field field = findField(owner.getClass(), fieldName);
      if (field == null) {
        return null;
      }
      field.setAccessible(true);
      return field.get(owner);
    }
    catch (Throwable ignored) {
      return null;
    }
  }

  private static Field findField(Class<?> ownerClass, String fieldName) {
    Class<?> current = ownerClass;
    while (current != null) {
      try {
        return current.getDeclaredField(fieldName);
      }
      catch (NoSuchFieldException ignored) {
        current = current.getSuperclass();
      }
    }
    return null;
  }

  private static Method findMethod(Class<?> ownerClass, String methodName, Class<?> parameterType) {
    return findMethod(ownerClass, methodName, new Class<?>[]{parameterType});
  }

  private static Method findMethod(Class<?> ownerClass, String methodName) {
    return findMethod(ownerClass, methodName, new Class<?>[0]);
  }

  private static Method findMethod(Class<?> ownerClass, String methodName, Class<?>[] parameterTypes) {
    Class<?> current = ownerClass;
    while (current != null) {
      Method[] methods = current.getDeclaredMethods();
      for (Method method : methods) {
        Class<?>[] actualParameterTypes = method.getParameterTypes();
        if (method.getName().startsWith(methodName) && Arrays.equals(actualParameterTypes, parameterTypes)) {
          return method;
        }
      }
      current = current.getSuperclass();
    }
    return null;
  }

  private static Object asCoroutineStackFrame(Object value) {
    return value != null && isCoroutineStackFrame(value) ? value : null;
  }

  private static boolean isCoroutineStackFrame(Object value) {
    Class<?> current = value.getClass();
    while (current != null) {
      Class<?>[] interfaces = current.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        if ("kotlin.coroutines.jvm.internal.CoroutineStackFrame".equals(anInterface.getName())) {
          return true;
        }
      }
      current = current.getSuperclass();
    }
    return false;
  }

  static List<StackTraceElement> getIndexedStackTraceForTests(Object owner, Object index, int limit) {
    CapturedStack stack = getIndexedStack(owner, normalizeIndex(index));
    return stack == null ? null : getStackTrace(stack, limit);
  }

  static CapturedStack getIndexedCapturedStackForTests(Object owner, Object index) {
    return getIndexedStack(owner, normalizeIndex(index));
  }

  private static int pushCurrentStack(CapturedStack stack) {
    return pushCurrentStack(stack, false);
  }

  private static int pushCurrentIndexedStack(CapturedStack stack) {
    return stack == null ? getStacksForCurrentThread().size() : pushCurrentStack(stack, true);
  }

  private static int pushCurrentStack(CapturedStack stack, boolean indexedMatch) {
    Deque<CurrentStackFrame> stacks = getStacksForCurrentThread();
    stacks.add(new CurrentStackFrame(stack, indexedMatch));
    return stacks.size();
  }

  private static int popCurrentStack() {
    Deque<CurrentStackFrame> stacks = getStacksForCurrentThread();
    CurrentStackFrame frame;
    while ((frame = stacks.pollLast()) != null) {
      if (!frame.myIndexedMatch) {
        break;
      }
    }
    return stacks.size();
  }

  static void clearCurrentStacksForTests() {
    getStacksForCurrentThread().clear();
  }

  static int getCurrentStackFrameCountForTests() {
    return getStacksForCurrentThread().size();
  }

  private static void logStorageEvent(String event, String details) {
    logStorageEvent(event, details, null, false);
  }

  private static void logStorageEvent(String event, String details, CapturedStack affectedStack) {
    logStorageEvent(event, details, affectedStack, true);
  }

  private static void logStorageEvent(String event, String details, CapturedStack affectedStack, boolean includeAffectedStack) {
    if (!DEBUG) {
      return;
    }
    try {
      Thread thread = Thread.currentThread();
      StringBuilder message = new StringBuilder();
      message.append("CaptureStorage.")
              .append(event)
              .append(" thread=")
              .append(getThreadText(thread))
              .append(" ")
              .append(details);
      if (includeAffectedStack) {
        message.append("\naffected stack: ");
        appendCapturedStackTrace(message, affectedStack, "  ");
      }
      message.append("\ncurrent async stack state: ");
      appendCurrentStacksDebugString(message, getStacksForCurrentThread());
      System.out.println(message.toString());
    }
    catch (Throwable t) {
      try {
        System.out.println("CaptureStorage debug logging failed: " + t);
      }
      catch (Throwable ignored) {
      }
    }
  }

  private static String getThreadText(Thread thread) {
    return thread.getName() + "@" + Integer.toHexString(System.identityHashCode(thread)) + "#" + thread.getId();
  }

  private static String getStackIdentity(CapturedStack stack) {
    return stack == null ? "null" : stack.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(stack));
  }

  private static CapturedStack peekCurrentStack(Deque<CurrentStackFrame> stacks) {
    CurrentStackFrame frame = stacks.peekLast();
    return frame == null ? null : frame.myStack;
  }

  private static CapturedStack getCurrentCapturedStackExcept(CapturedStack ignoredStack) {
    if (ignoredStack == null) {
      return getCurrentCapturedStack();
    }
    Iterator<CurrentStackFrame> iterator = getStacksForCurrentThread().descendingIterator();
    while (iterator.hasNext()) {
      CapturedStack stack = iterator.next().myStack;
      if (stack != ignoredStack) {
        return stack;
      }
    }
    return null;
  }

  private static void appendCapturedStackTrace(StringBuilder message, CapturedStack stack, String linePrefix) {
    if (stack == null) {
      message.append("null");
      return;
    }
    message.append(getStackIdentity(stack));
    List<StackTraceElement> stackTrace = getStackTrace(stack, DEBUG_ASYNC_STACK_TRACE_LIMIT);
    if (stackTrace.isEmpty()) {
      message.append("\n").append(linePrefix).append("<empty>");
      return;
    }
    int count = 0;
    for (StackTraceElement element : stackTrace) {
      if (count >= DEBUG_ASYNC_STACK_TRACE_LIMIT) {
        message.append("\n").append(linePrefix).append("... truncated at ").append(DEBUG_ASYNC_STACK_TRACE_LIMIT).append(" frames");
        break;
      }
      message.append("\n").append(linePrefix);
      if (element == ASYNC_STACK_ELEMENT) {
        message.append("--- async boundary ---");
      }
      else {
        message.append("at ").append(element);
      }
      count++;
    }
  }

  private static void appendCurrentStacksDebugString(StringBuilder message, Deque<CurrentStackFrame> stacks) {
    message.append("frames=").append(stacks.size());
    if (stacks.isEmpty()) {
      message.append("\n  <empty>");
      return;
    }
    int index = 0;
    for (CurrentStackFrame frame : stacks) {
      message.append("\n  frame[").append(index).append("] type=");
      message.append(frame.myIndexedMatch ? "indexed-match" : "insert");
      message.append(" ");
      appendCapturedStackTrace(message, frame.myStack, "    ");
      index++;
    }
  }

  private static class CurrentStackFrame {
    private final CapturedStack myStack;
    private final boolean myIndexedMatch;

    private CurrentStackFrame(CapturedStack stack, boolean indexedMatch) {
      myStack = stack;
      myIndexedMatch = indexedMatch;
    }
  }

  private static boolean runWithOverheadTrackingAndWithoutThrowableCapture(ThreadLocalContext context, final Runnable runnable) {
  // It's better to disable throwable instrumentation inside our own code for ease of debugging.
    boolean oldValue = context.throwableCaptureDisabled;
    context.throwableCaptureDisabled = true;
    try {
      return context.overheadTracker.runIfNoOverhead(runnable);
    } finally {
      context.throwableCaptureDisabled = oldValue;
    }
  }

  private static void runWithoutThrowableCapture(ThreadLocalContext context, final Runnable runnable) {
    // It's better to disable throwable instrumentation inside our own code for ease of debugging.
    boolean oldValue = context.throwableCaptureDisabled;
    context.throwableCaptureDisabled = true;
    try {
      runnable.run();
    } finally {
      context.throwableCaptureDisabled = oldValue;
    }
  }

  // It's better to disable throwable instrumentation inside our own code for ease of debugging.
  private static <T> T withoutThrowableCapture(Callable<T> action) {
    ThreadLocalContext context = CURRENT_CONTEXT.get();
    boolean oldValue = context.throwableCaptureDisabled;
    context.throwableCaptureDisabled = true;
    try {
      return action.call();
    } finally {
      context.throwableCaptureDisabled = oldValue;
    }
  }

  private static Method getGetCallerFrameMethod(Object key) throws NoSuchMethodException, ClassNotFoundException {
    ClassLoader classLoader = key.getClass().getClassLoader();
    Method getCallerFrameMethod = COROUTINE_GET_CALLER_FRAME_METHODS.get(classLoader);
    if (getCallerFrameMethod == null) {
      getCallerFrameMethod = Class.forName("kotlin.coroutines.jvm.internal.CoroutineStackFrame", false, classLoader)
              .getDeclaredMethod("getCallerFrame");
      COROUTINE_GET_CALLER_FRAME_METHODS.put(classLoader, getCallerFrameMethod);
    }
    return getCallerFrameMethod;
  }

  private static class ConcurrentIdentityWeakHashMap<K, V> {
    private final ReferenceQueue<K> referenceQueue = new ReferenceQueue<>();
    private final ConcurrentMap<Key<K>, V> map = new ConcurrentHashMap<>();

    @SuppressWarnings("UnusedReturnValue")
    public V put(K key, V value) {
      processQueue();
      return map.put(new WeakKey<>(key, referenceQueue), value);
    }

    public V putIfAbsent(K key, V value) {
      processQueue();
      return map.putIfAbsent(new WeakKey<>(key, referenceQueue), value);
    }

    public V get(K key) {
      return map.get(new HardKey<>(key));
    }

    public V remove(K key) {
      processQueue();
      return map.remove(new HardKey<>(key));
    }

    private void processQueue() {
      WeakKey<K> key;
      //noinspection unchecked
      while ((key = (WeakKey<K>) referenceQueue.poll()) != null) {
        map.remove(key);
      }
    }

    private interface Key<K> {
      K get();
    }

    private static boolean equalKeys(Key<?> x, Key<?> y) {
      if (x == y) return true;
      Object kx = x.get();
      Object ky = y.get();
      return kx != null && kx == ky;
    }

    // only for map queries
    private static class HardKey<K> implements Key<K> {
      private final K myKey;
      private final int myHash;

      HardKey(K key) {
        myKey = key;
        myHash = System.identityHashCode(key);
      }

      @Override
      public K get() {
        return myKey;
      }

      @Override
      public boolean equals(Object o) {
        return o instanceof Key<?> && equalKeys(this, (Key<?>) o);
      }

      public int hashCode() {
        return myHash;
      }
    }

    private static class WeakKey<K> extends WeakReference<K> implements Key<K> {
      private final int myHash;

      WeakKey(K key, ReferenceQueue<K> q) {
        super(key, q);
        myHash = System.identityHashCode(key);
      }

      @Override
      public boolean equals(Object o) {
        return o instanceof Key<?> && equalKeys(this, (Key<?>) o);
      }

      @Override
      public int hashCode() {
        return myHash;
      }
    }
  }

  private static CapturedStack createCapturedStack(Throwable exception, CapturedStack insertMatch) {
    return appendCapturedStack(new ExceptionCapturedStack(exception), insertMatch);
  }

  private static CapturedStack appendCapturedStack(CapturedStack current, CapturedStack insertMatch) {
    if (insertMatch != null) {
      CapturedStack stack = new DeepCapturedStack(current, insertMatch);
      if (stack.getRecursionDepth() > 100) {
        ArrayList<StackTraceElement> trace = getStackTrace(stack, 500);
        trace.trimToSize();
        stack = new UnwindCapturedStack(trace);
      }
      return stack;
    }
    return current;
  }

  private static class StackData {
    public final List<StackTraceElement> stackTrace;
    public final CapturedStack previous;

    private StackData(List<StackTraceElement> stackTrace, CapturedStack previous) {
      this.stackTrace = stackTrace;
      this.previous = previous;
    }
  }

  static abstract class CapturedStack {
    abstract List<StackTraceElement> getStackTrace();

    int getRecursionDepth() {
      return 0;
    }

    StackData collectStacks(List<StackTraceElement> stackTrace) {
      return new StackData(stackTrace, null);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (StackTraceElement se: getStackTrace()) {
        sb.append(se).append("\n");
      }
      return "current_stack:[ + " + sb.toString() + "\n]";
    }
  }

  private static class UnwindCapturedStack extends CapturedStack {
    final List<StackTraceElement> myStackTraceElements;

    UnwindCapturedStack(List<StackTraceElement> elements) {
      myStackTraceElements = elements;
    }

    @Override
    public List<StackTraceElement> getStackTrace() {
      return myStackTraceElements;
    }
  }

  private static class ExceptionCapturedStack extends CapturedStack {
    final Throwable myException;

    private ExceptionCapturedStack(Throwable exception) {
      myException = exception;
    }

    @Override
    public List<StackTraceElement> getStackTrace() {
      return trimInitAgentFrames(Arrays.asList(myException.getStackTrace()));
    }
  }

  private static class DeepCapturedStack extends CapturedStack {
    private final CapturedStack myCurrent;
    private final CapturedStack myPrevious;
    private final int myRecursionDepth;

    DeepCapturedStack(CapturedStack stack, CapturedStack previous) {
      myCurrent = stack;
      myPrevious = previous;
      myRecursionDepth = previous.getRecursionDepth() + 1;
    }

    @Override
    public List<StackTraceElement> getStackTrace() {
      return myCurrent.getStackTrace();
    }

    @Override
    public int getRecursionDepth() {
      return myRecursionDepth;
    }

    @Override
    StackData collectStacks(List<StackTraceElement> stackTrace) {
      int size = stackTrace.size();
      int newEnd = Integer.MAX_VALUE;
      for (int i = 0; i < size; i++) {
        StackTraceElement elem = stackTrace.get(i);
        if (elem.getMethodName().endsWith(GENERATED_INSERT_METHOD_POSTFIX)) {
          // End stack trace like this: ..., "foo$$$capture", "foo"
          newEnd = i + 2;
          break;
        } else if (elem == ASYNC_STACK_ELEMENT) {
          newEnd = i;
          break;
        }
      }
      if (newEnd > size) {
        return new StackData(stackTrace, null); // Insertion point was not found - stop
      } else {
        return new StackData(stackTrace.subList(0, newEnd), myPrevious);
      }
    }
  }

  static List<StackTraceElement> getThrowableStackTrace(Throwable throwable) {
    return trimInitAgentFrames(Arrays.asList(throwable.getStackTrace()));
  }

  static List<StackTraceElement> getCapturedStackTrace(CapturedStack capturedStack, int limit) {
    return getStackTrace(capturedStack, limit);
  }

  // to be run from the debugger

  /**
   * If storing stack traces for all threads is enabled (`debugger.async.stack.trace.for.all.threads` is true),
   * returns the captured stack trace of the given thread or null if no stack trace was captured.
   * <p>
   * If `debugger.async.stack.trace.for.all.threads` is false,
   * it only returns the captured stack trace for the current thread and null if no stack trace was captured or if the given thread is not the current thread.
   */
  @SuppressWarnings("unused")
  public static String getCapturedStackForThread(int limit, Thread thread) {
    Deque<CurrentStackFrame> capturedStacks = storeAsyncStackTracesForAllThreads
            ? THREAD_TO_STACKS_MAP.get(thread)
            : (thread == Thread.currentThread() ? CURRENT_STACKS.get() : null);
    if (capturedStacks == null) return null;
    return wrapInString(peekCurrentStack(capturedStacks), limit);
  }

  /**
   * If storing stack traces for all threads is enabled (`debugger.async.stack.trace.for.all.threads` is true),
   * returns a map from thread to it's captured stack trace.
   * <p>
   * If `debugger.async.stack.trace.for.all.threads` is false,
   * only returns a map from the current thread to its captured stack trace.
   */
  @SuppressWarnings("unused")
  public static Map<Thread, String> getAllCapturedStacks(int limit) {
    HashMap<Thread, String> threadToStacks = new HashMap<>();
    if (storeAsyncStackTracesForAllThreads) {
      for (Map.Entry<ConcurrentIdentityWeakHashMap.Key<Thread>, Deque<CurrentStackFrame>> entry : THREAD_TO_STACKS_MAP.map.entrySet()) {
        Thread thread = entry.getKey().get();
        if (entry.getValue() == null || entry.getValue().isEmpty() || thread == null || !thread.isAlive()) continue;
        String capturedStack = wrapInString(peekCurrentStack(entry.getValue()), limit);
        threadToStacks.put(thread, capturedStack);
      }
    } else {
      Deque<CurrentStackFrame> capturedStacks = CURRENT_STACKS.get();
      if (capturedStacks != null) {
        threadToStacks.put(Thread.currentThread(), wrapInString(peekCurrentStack(capturedStacks), limit));
      }
    }
    return threadToStacks;
  }

  // to be run from the debugger
  @SuppressWarnings("unused")
  public static Object[][] getRelatedStack(Object key, int limit) {
      return wrapInArray(STORAGE_GENERAL.get(key), limit);
  }

  private static String wrapInString(CapturedStack stack, int limit) {
    if (stack == null) {
      return null;
    }
    return wrapAsyncStackTraceInString(getStackTrace(stack, limit));
  }

  private static String wrapAsyncStackTraceInString(List<StackTraceElement> stackTrace) {
    if (stackTrace == null || stackTrace.isEmpty()) {
      return null;
    }
    try (ByteArrayOutputStream bas = new ByteArrayOutputStream();
         DataOutputStream dos = new DataOutputStream(bas)) {
      writeAsyncStackTraceToStream(stackTrace, dos);
      return bas.toString(StandardCharsets.ISO_8859_1.name());
    } catch (IOException e) {
      // It shouldn't ever happen.
      handleException(e);
      return null;
    }
  }

  static void writeAsyncStackTraceToStream(List<StackTraceElement> stackTrace, DataOutputStream dos) throws IOException {
    for (StackTraceElement elem : stackTrace) {
      writeAsyncStackTraceElementToStream(elem, dos);
    }
  }

  static void writeAsyncStackTraceElementToStream(StackTraceElement elem, DataOutputStream dos) throws IOException {
    if (elem == ASYNC_STACK_ELEMENT) {
      dos.writeBoolean(false);
    }
    else {
      dos.writeBoolean(true);
      dos.writeUTF(elem.getClassName());
      dos.writeUTF(elem.getMethodName());
      dos.writeInt(elem.getLineNumber());
    }
  }

  private static Object[][] wrapInArray(CapturedStack stack, int limit) {
    if (stack == null) {
      return null;
    }
    List<StackTraceElement> stackTrace = getStackTrace(stack, limit);
    Object[][] res = new Object[stackTrace.size()][];
    for (int i = 0; i < stackTrace.size(); i++) {
      StackTraceElement elem = stackTrace.get(i);
      if (elem == ASYNC_STACK_ELEMENT) {
        res[i] = null;
      }
      else {
        res[i] = new Object[]{elem.getClassName(), elem.getFileName(), elem.getMethodName(), String.valueOf(elem.getLineNumber())};
      }
    }
    return res;
  }

  private static List<StackTraceElement> trimInitAgentFrames(List<StackTraceElement> elements) {
    int firstNotAgent = 0;
    for (int i = 0; i < elements.size(); i++) {
      if (!isAgentFrame(elements.get(i))) {
        firstNotAgent = i;
        break;
      }
    }
    return elements.subList(firstNotAgent, elements.size());
  }

  private static ArrayList<StackTraceElement> getStackTrace(CapturedStack stack, int limit) {
    ArrayList<StackTraceElement> res = new ArrayList<>();
    while (stack != null && res.size() <= limit) {
      List<StackTraceElement> filteredStacks = stack.getStackTrace();
      StackData stackData = stack.collectStacks(filteredStacks);
      res.addAll(stackData.stackTrace);
      stack = stackData.previous;
      if (stack != null) {
        res.add(ASYNC_STACK_ELEMENT);
      }
    }
    return res;
  }

  public static void setEnabled(boolean enabled) {
    ENABLED = enabled;
  }

  private static void handleException(Throwable e) {
    ENABLED = false;
    System.err.println("Critical error in IDEA Async Stacktraces instrumenting agent. Agent is now disabled. Please report to IDEA support:");
    e.printStackTrace();
  }

  static boolean isAgentFrame(StackTraceElement elem) {
    return elem.getClassName().startsWith(PACKAGE_PREFIX);
  }

  static List<StackTraceElement> getCurrentStackTraceWithoutAgentFrames() {
    // Don't use Thread.currentThread().getStackTrace() because it adds extra frame.
    return trimInitAgentFrames(Arrays.asList(new Throwable().getStackTrace()));
  }

  /** Expensive method, it should be used only for logging. */
  private static String getCallerDescriptorForLogging() {
    List<StackTraceElement> stackTrace = getCurrentStackTraceWithoutAgentFrames();
    if (stackTrace.isEmpty()) return "unknown";

    StackTraceElement elem = stackTrace.get(0);
    return elem.getClassName() + "." + elem.getMethodName();
  }

  private static String getKeyText(Object key) {
    String res = key.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(key));
    try {
      return res + "(" + key + ")";
    } catch (RuntimeException ignored) {
    }
    return res;
  }

  private static String getNullableKeyText(Object key) {
    return key == null ? "null" : getKeyText(key);
  }

  private static String getIndexedKeyText(Object owner, Object index) {
    String indexText = index == NULL_INDEX ? "null" : String.valueOf(index);
    return getKeyText(owner) + "[" + indexText + "]";
  }

  private static class DeferredCapturedStack extends CapturedStack {
    private volatile CapturedStack myStack;

    private void setStack(CapturedStack stack) {
      myStack = stack;
    }

    @Override
    List<StackTraceElement> getStackTrace() {
      CapturedStack stack = myStack;
      return stack == null ? Collections.<StackTraceElement>emptyList() : stack.getStackTrace();
    }

    @Override
    int getRecursionDepth() {
      CapturedStack stack = myStack;
      return stack == null ? 0 : stack.getRecursionDepth();
    }

    @Override
    StackData collectStacks(List<StackTraceElement> stackTrace) {
      CapturedStack stack = myStack;
      return stack == null ? new StackData(Collections.<StackTraceElement>emptyList(), null) : stack.collectStacks(stackTrace);
    }
  }

  private static class ThrottledCapturedStack extends CapturedStack {

    public static final ThrottledCapturedStack INSTANCE = new ThrottledCapturedStack();

    private static final List<StackTraceElement> STACK_TRACE_ELEMENTS = Collections.singletonList(THROTTLED_STACK_ELEMENT);


    private ThrottledCapturedStack() {
    }

    @Override
    public List<StackTraceElement> getStackTrace() {
      return STACK_TRACE_ELEMENTS;
    }
  }
}
