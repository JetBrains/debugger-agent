// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.rt.debugger.agent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SuppressWarnings({"UseOfSystemOutOrSystemErr"})
public final class CaptureStorage {
  public static final String GENERATED_INSERT_METHOD_POSTFIX = "$$$capture";
  private static final ConcurrentIdentityWeakHashMap<Object, CapturedStack> STORAGE = new ConcurrentIdentityWeakHashMap<>();

  private static final ThreadLocal<Deque<CapturedStack>> CURRENT_STACKS = new ThreadLocal<Deque<CapturedStack>>() {
    @Override
    protected Deque<CapturedStack> initialValue() {
      return new LinkedList<>();
    }
  };

  private static final ThreadLocal<Boolean> THROWABLE_CAPTURE_DISABLED = new ThreadLocal<Boolean>() {
    @Override
    protected Boolean initialValue() {
      return false;
    }
  };

  @SuppressWarnings("StaticNonFinalField")
  public static boolean DEBUG; // set from debugger
  private static boolean ENABLED = true;

  private static final StackTraceElement ASYNC_STACK_ELEMENT =
          new StackTraceElement("--- Async", "Stack.Trace --- ", "captured by IntelliJ IDEA debugger", -1);

  //// METHODS CALLED FROM THE USER PROCESS

  @SuppressWarnings("unused")
  public static void capture(final Object key) {
    withoutThrowableCapture(new Runnable() {
        @Override
        public void run() {
            captureInt(new Throwable(), key);
        }
    });
  }

  @SuppressWarnings("unused")
  public static void captureThrowable(final Throwable throwable) {
    if (THROWABLE_CAPTURE_DISABLED.get()) {
      return;
    }
    withoutThrowableCapture(new Runnable() {
      @Override
      public void run() {
        // TODO: support coroutine stack traces
        captureInt(throwable, throwable);
      }
    });
  }

  @SuppressWarnings("unused")
  private static void captureInt(final Throwable exception, final Object key) {
    if (!ENABLED) {
      return;
    }
    withoutThrowableCapture(new Runnable() {
      @Override
      public void run() {
        try {
          if (DEBUG) {
            System.out.println("capture " + getCallerDescriptorForLogging() + " - " + getKeyText(key));
          }
          putCapturedStack(key, exception, CURRENT_STACKS.get().peekLast());
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
    withoutThrowableCapture(new Runnable() {
      @Override
      public void run() {
        try {
          CapturedStack stack = getCapturedStack(key);
          Deque<CapturedStack> currentStacks = CURRENT_STACKS.get();
          currentStacks.add(stack);
          if (DEBUG) {
            System.out.println(
                    "insert " + getCallerDescriptorForLogging() + " -> " + getKeyText(key) + ", stack saved (" + currentStacks.size() + ")");
          }
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
    withoutThrowableCapture(new Runnable() {
      @Override
      public void run() {
        try {
          Deque<CapturedStack> currentStacks = CURRENT_STACKS.get();
          // frameworks may modify thread locals to avoid memory leaks, so do not fail if currentStacks is empty
          // check https://youtrack.jetbrains.com/issue/IDEA-357455 for more details
          currentStacks.pollLast();
          if (DEBUG) {
            System.out.println(
                    "insert " + getCallerDescriptorForLogging() + " <- " + getKeyText(key) + ", stack removed (" + currentStacks.size() + ")");
          }
        } catch (Exception e) {
          handleException(e);
        }
      }
    });
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
  public static StackTraceElement[] getAsyncStackTrace(final Throwable key) {
    if (!ENABLED) {
      return key.getStackTrace();
    }
    return withoutThrowableCapture(new Callable<StackTraceElement[]>() {
      @Override
      public StackTraceElement[] call() {
        try {
          CapturedStack capturedStack = getCapturedStack(key);
          if (capturedStack != null) {
            ArrayList<StackTraceElement> stackTrace = getStackTrace(capturedStack, CaptureAgent.throwableAsyncStackDepthLimit());
            return stackTrace.toArray(new StackTraceElement[0]);
          }
          // E.g., there is no captured stack if the exception was instantiated before the agent was loaded.
        } catch (Exception e) {
          handleException(e);
        }
        return key.getStackTrace();
      }
    });
  }

  //// END - METHODS CALLED FROM THE USER PROCESS

  private interface Callable<T> {
    T call();
  }

  // It's better to disable throwable instrumentation inside our own code for ease of debugging.
  private static void withoutThrowableCapture(final Runnable runnable) {
    withoutThrowableCapture(new Callable<Void>() {
      @Override
      public Void call() {
        runnable.run();
        return null;
      }
    });
  }

  // It's better to disable throwable instrumentation inside our own code for ease of debugging.
  private static <T> T withoutThrowableCapture(Callable<T> action) {
    Boolean oldValue = THROWABLE_CAPTURE_DISABLED.get();
    THROWABLE_CAPTURE_DISABLED.set(true);
    try {
      return action.call();
    } finally {
      THROWABLE_CAPTURE_DISABLED.set(oldValue);
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

    public V get(K key) {
      return map.get(new HardKey<>(key));
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
    if (insertMatch != null) {
      CapturedStack stack = new DeepCapturedStack(exception, insertMatch);
      if (stack.getRecursionDepth() > 100) {
        ArrayList<StackTraceElement> trace = getStackTrace(stack, 500);
        trace.trimToSize();
        stack = new UnwindCapturedStack(trace);
      }
      return stack;
    }
    return new ExceptionCapturedStack(exception);
  }

  private static void putCapturedStack(Object key, Throwable exception, CapturedStack insertMatch) {
    if (key instanceof Throwable) {
      if (insertMatch != null) {
        assert !(insertMatch instanceof ExceptionCapturedStack) || ((ExceptionCapturedStack) insertMatch).myException != key;
        STORAGE.put(key, insertMatch);
      }
    } else {
      STORAGE.put(key, createCapturedStack(exception, insertMatch));
    }
  }

  private static CapturedStack getCapturedStack(Object key) {
    CapturedStack capturedStack = STORAGE.get(key);
    if (key instanceof Throwable) {
      return createCapturedStack(((Throwable) key), capturedStack);
    }
    return capturedStack;
  }

  private interface CapturedStack {
    List<StackTraceElement> getStackTrace();
    int getRecursionDepth();
  }

  private static class UnwindCapturedStack implements CapturedStack {
    final List<StackTraceElement> myStackTraceElements;

    UnwindCapturedStack(List<StackTraceElement> elements) {
      myStackTraceElements = elements;
    }

    @Override
    public List<StackTraceElement> getStackTrace() {
      return myStackTraceElements;
    }

    @Override
    public int getRecursionDepth() {
      return 0;
    }
  }

  private static class ExceptionCapturedStack implements CapturedStack {
    final Throwable myException;

    private ExceptionCapturedStack(Throwable exception) {
      myException = exception;
    }

    @Override
    public List<StackTraceElement> getStackTrace() {
      return Arrays.asList(myException.getStackTrace());
    }

    @Override
    public int getRecursionDepth() {
      return 0;
    }
  }

  private static class DeepCapturedStack extends ExceptionCapturedStack {
    final CapturedStack myInsertMatch;
    final int myRecursionDepth;

    DeepCapturedStack(Throwable exception, CapturedStack insertMatch) {
      super(exception);
      myInsertMatch = insertMatch;
      myRecursionDepth = insertMatch.getRecursionDepth() + 1;
    }

    @Override
    public int getRecursionDepth() {
      return myRecursionDepth;
    }
  }

  // to be run from the debugger
  @SuppressWarnings("unused")
  public static String getCurrentCapturedStack(int limit) throws IOException {
    return wrapInString(CURRENT_STACKS.get().peekLast(), limit);
  }

  // to be run from the debugger
  @SuppressWarnings("unused")
  public static Object[][] getRelatedStack(Object key, int limit) {
    return wrapInArray(getCapturedStack(key), limit);
  }

  private static String wrapInString(CapturedStack stack, int limit) throws IOException {
    if (stack == null) {
      return null;
    }
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bas);
    for (StackTraceElement elem : getStackTrace(stack, limit)) {
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
    return bas.toString("ISO-8859-1");
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
      if (isNotAgentFrame(elements.get(i))) {
        firstNotAgent = i;
        break;
      }
    }
    return elements.subList(firstNotAgent, elements.size());
  }

  private static ArrayList<StackTraceElement> getStackTrace(CapturedStack stack, int limit) {
    ArrayList<StackTraceElement> res = new ArrayList<>();
    while (stack != null && res.size() <= limit) {
      List<StackTraceElement> stackTrace = trimInitAgentFrames(stack.getStackTrace());
      if (stack instanceof DeepCapturedStack) {
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
          stack = null; // Insertion point was not found - stop
        }
        else {
          stackTrace = stackTrace.subList(0, newEnd);
          stack = ((DeepCapturedStack)stack).myInsertMatch;
        }
      }
      else {
        stack = null;
      }
      res.addAll(stackTrace);
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
    //noinspection CallToPrintStackTrace
    e.printStackTrace();
  }

  private static boolean isNotAgentFrame(StackTraceElement elem) {
    return !elem.getClassName().startsWith(CaptureStorage.class.getPackage().getName());
  }

  /** Expensive method, it should be used only for logging. */
  private static String getCallerDescriptorForLogging() {
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    // skip the first one -- it's Thread.getStackTrace
    for (int i = 1; i < stackTrace.length; i++) {
      StackTraceElement elem = stackTrace[i];
      if (isNotAgentFrame(elem)) {
        return elem.getClassName() + "." + elem.getMethodName();
      }
    }
    return "unknown";
  }

  private static String getKeyText(Object key) {
    String res = key.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(key));
    try {
      return res + "(" + key + ")";
    } catch (RuntimeException ignored) {
    }
    return res;
  }
}
