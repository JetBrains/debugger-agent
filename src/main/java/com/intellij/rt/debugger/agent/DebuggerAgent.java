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
  public static void premain(String args, Instrumentation instrumentation) {
    // never instrument twice
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
  }

  private static void readAndApplyProperties(String uri, Instrumentation instrumentation) {
    Properties properties = new Properties();

    File file = null;
    if (uri != null && !uri.isEmpty()) {
      try {
        InputStream stream = null;
        try {
          file = new File(new URI(uri));
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
    }

    initAll(instrumentation, properties);

    // delete settings file only if it was read correctly
    if (Boolean.parseBoolean(properties.getProperty("deleteSettings", "true"))) {
      if (file != null) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
      }
    }
  }

  private static void initSharedFlowTransformer(Instrumentation instrumentation) {
    if (Boolean.getBoolean("debugger.agent.enable.coroutines")
            && Boolean.getBoolean("kotlinx.coroutines.debug.enable.flows.stack.trace")) {
      instrumentation.addTransformer(new SharedFlowTransformer(Boolean.getBoolean("kotlinx.coroutines.debug.flows.double.wrapping.strict.check")), true);
    }
  }
}
