package com.intellij.rt.debugger.agent;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class SuspendHelper {
  public static void init(Properties properties) {
    if (!Boolean.parseBoolean(properties.getProperty("suspendHelper", "false"))) {
      return;
    }
    DebuggerAgent.SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(new Runnable() {
      @Override
      public void run() {
        suspendHelperLoopBody();
      }
    }, 0, 50, TimeUnit.MILLISECONDS);
  }

  // It may be used to suspend this thread by the debugger (so stop busy waiting)
  // and resume it to ensure suspend-all will happen.
  private static void suspendHelperLoopBody() {
  }
}
