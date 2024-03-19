package com.intellij.rt.debugger.agent;

import java.util.Properties;

public class SuspendHelper {
  public static void init(Properties properties) {
    if (!Boolean.parseBoolean(properties.getProperty("suspendHelper", "false"))) {
      return;
    }
    Thread intelliJSuspendHelper = new Thread(new Runnable() {
      @Override
      public void run() {
        while (true) {
          try {
            suspendHelperLoopBody();
          } catch (InterruptedException e) {
            break;
          }
        }
      }
    }, "IntelliJ Suspend Helper");
    intelliJSuspendHelper.setDaemon(true);
    intelliJSuspendHelper.start();
  }

  // It may be used to suspend this thread by the debugger (so stop busy waiting)
  // and resume it to ensure suspend-all will happen.
  private static void suspendHelperLoopBody() throws InterruptedException {
    Thread.sleep(50);
  }
}
