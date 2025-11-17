// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.rt.debugger.agent;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.util.Properties;

@SuppressWarnings("UseOfSystemOutOrSystemErr")
public class DebuggerAgent {
  private static final String KEEP_SUFFIX = "[keep]";

  public static void premain(String args, Instrumentation instrumentation) {
    if (DebuggerAgent.class.getClassLoader() != null) {
      System.err.println("Debugger agent: agent should be loaded by bootstrap classloader, " +
              "check jar name and 'Boot-Class-Path' value in the manifest");
      return;
    }

    if (System.getProperty("intellij.debug.agent") != null) {
      System.err.println("Debugger agent: more than one agent is not allowed, skipping");
      return;
    }
    System.setProperty("intellij.debug.agent", "true");

    readAndApplyProperties(args, instrumentation);
  }

  private static void initAll(Instrumentation instrumentation, Properties properties) {
    initSharedFlowTransformer(instrumentation);
    CaptureAgent.init(properties, instrumentation);
    SuspendHelper.init(properties);
    CollectionBreakpointInstrumentor.init(instrumentation);
    SpilledVariablesTransformer.init(instrumentation);
  }

  private static void readAndApplyProperties(String args, Instrumentation instrumentation) {
    Properties properties = new Properties();
    if (args == null || args.isEmpty()) {
      initAll(instrumentation, properties);
      return;
    }
    // Parse "keep setting file" suffix: -javaagent:<path>/debugger-agent.jar=<uri-or-path-to-props>([keep])?
    String path;
    Boolean keepSettings = null;
    if (args.endsWith(KEEP_SUFFIX)) {
        path = args.substring(0, args.length() - KEEP_SUFFIX.length());
        keepSettings = true;
    } else {
        path = args;
    }

    File file = null;
    try {
      InputStream stream = null;
      try {
        if (path.startsWith("file:")) {
          file = new File(new URI(path));
        } else {
          file = new File(path);
        }
        stream = new FileInputStream(file);
        // use ISO 8859-1 character encoding
        properties.load(stream);
      } finally {
        if (stream != null) {
          stream.close();
        }
      }
    } catch (Exception e) {
      System.out.println("Capture agent: unable to read settings");
      e.printStackTrace();
    }

    initAll(instrumentation, properties);

    // delete settings file only if it was read correctly
    boolean keep = keepSettings != null || !Boolean.parseBoolean(properties.getProperty("deleteSettings", "true"));
    if (!keep) {
      if (file != null) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
      }
    }
  }

  private static void initSharedFlowTransformer(Instrumentation instrumentation) {
    if (Boolean.getBoolean("debugger.agent.enable.coroutines")
            && Boolean.getBoolean("kotlinx.coroutines.debug.enable.flows.stack.trace")) {
      instrumentation.addTransformer(new SharedFlowTransformer(), true);
      // make state flow instrumentation switchable, so it could be turned off if instrumentation breaks the code
      if (Boolean.getBoolean("kotlinx.coroutines.debug.enable.mutable.state.flows.stack.trace")) {
        instrumentation.addTransformer(new StateFlowTransformer(), true);
      }
    }
  }
}
