package com.intellij.rt.debugger.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class SuspendHelper {
  private static boolean isEnabled;
  private static final List<Runnable> listeners = new ArrayList<>();

  public static void init(Properties properties) {
    isEnabled = Boolean.parseBoolean(properties.getProperty("suspendHelper", "false"));
    if (!isEnabled) {
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
          for (Runnable listener : listeners) {
            try {
              listener.run();
            } catch (Throwable e) {
              System.err.println("Error in suspend helper listener");
              e.printStackTrace(System.err);
            }
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

  static boolean addPeriodicListener(Runnable listener) {
    if (!isEnabled) return false;
    listeners.add(listener);
    return true;
  }
}
