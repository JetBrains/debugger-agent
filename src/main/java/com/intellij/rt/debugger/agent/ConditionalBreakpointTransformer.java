package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.*;
import org.jetbrains.capture.org.objectweb.asm.tree.ClassNode;
import org.jetbrains.capture.org.objectweb.asm.tree.LocalVariableNode;
import org.jetbrains.capture.org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.*;

import static com.intellij.rt.debugger.agent.CaptureAgent.getInternalClsName;

class InstrumentationBpExceptionWrapper extends RuntimeException {
    final int instrumentationId;

    InstrumentationBpExceptionWrapper(Throwable e, int instrumentationId) {
        super(e);
        this.instrumentationId = instrumentationId;
    }
}

class InstrumentationBreakpointInfo {
    final Integer lineNumber;
    final String fragmentClassName;
    final String methodSignature;
    final List<String> argumentNames;

    InstrumentationBreakpointInfo(Integer lineNumber, String fragmentClassName, String methodSignature, List<String> argumentNames) {
        this.lineNumber = lineNumber;
        this.fragmentClassName = fragmentClassName;
        this.methodSignature = methodSignature;
        this.argumentNames = argumentNames;
    }
}

public class ConditionalBreakpointTransformer {
    private static final String conditionCheckMethodName = "conditionCheck";

    private static final Map<String, Map<String, Map<Integer, InstrumentationBreakpointInfo>>> myBreakpoints = new LinkedHashMap<>();

    public static void init(Properties properties, Instrumentation instrumentation) {
        applyProperties(properties);
        instrumentation.addTransformer(new BreakpointInstrumentalist(), true);
    }

    private static void applyProperties(Properties properties) {
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            addPoint((String)entry.getKey(), (String)entry.getValue());
        }
    }

    private static void addPoint(String propertyKey, String propertyValue) {
        if (propertyKey.startsWith("instrumentation.condition.breakpoint")) {
            String[] split = propertyValue.split(" ");
            if (split.length >= 4) {
                addBreakpoint(Arrays.asList(split));
            }
        }
    }

    public static void addBreakpoint(List<String> split) {
        String className = split.get(0);
        String methodName = split.get(1);
        int lineNumber = Integer.parseInt(split.get(2));
        String fragmentClassName = split.get(3);
        String methodSignature = split.get(4);
        List<String> argumentNames = split.subList(5, split.size());

        Map<String, Map<Integer, InstrumentationBreakpointInfo>> methods = myBreakpoints.get(className);
        if (methods == null) {
            methods = new LinkedHashMap<>();
            myBreakpoints.put(className, methods);
        }
        Map<Integer, InstrumentationBreakpointInfo> lineNumbers = getLineNumbers(methodName, methods);
        if (lineNumbers == null) {
            lineNumbers = new LinkedHashMap<>();
            methods.put(methodName, lineNumbers);
        }
        lineNumbers.put(lineNumber, new InstrumentationBreakpointInfo(lineNumber, fragmentClassName, methodSignature, argumentNames));
    }

    private static class BreakpointInstrumentalist implements ClassFileTransformer {
        @Override
        public byte[] transform(final ClassLoader loader, final String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            final Map<String, Map<Integer, InstrumentationBreakpointInfo>> methods = myBreakpoints.get(className);
            if (methods == null || methods.isEmpty()) {
                return null;
            }

            try {
                final ClassNode classNode = new ClassNode();
                new ClassReader(classfileBuffer).accept(classNode, 0);

                ClassTransformer transformer = new ClassTransformer(
                        className, classfileBuffer, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader
                );

                return transformer.accept(new ClassVisitor(Opcodes.API_VERSION, transformer.writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor superMethodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                        final Map<Integer, InstrumentationBreakpointInfo> lineNumbers = getLineNumbers(name, methods);
                        if (lineNumbers == null || lineNumbers.isEmpty()) {
                            return superMethodVisitor;
                        }

                        MethodNode m = null;
                        for (MethodNode method : classNode.methods) {
                            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                                m = method;
                            }
                        }
                        if (m == null) {
                            return superMethodVisitor;
                        }
                        final MethodNode methodNode = m;

                        return new MethodVisitor(api, superMethodVisitor) {

                            @Override
                            public void visitLineNumber(int line, Label start) {
                                InstrumentationBreakpointInfo instrumentationBreakpointInfo = lineNumbers.get(line);
                                if (instrumentationBreakpointInfo != null) {
                                    addInstrumentationCondition(instrumentationBreakpointInfo, start);
                                }
                                super.visitLineNumber(line, start);
                            }

                            private void addInstrumentationCondition(InstrumentationBreakpointInfo instrumentationBreakpointInfo, Label start) {
                                String fragmentClassName = instrumentationBreakpointInfo.fragmentClassName;
                                int instrumentationId = extractIdFromFragmentClassName(fragmentClassName);
                                try {
                                    Label startTry = new Label();
                                    Label endTry = new Label();
                                    Label catchBlock = new Label();
                                    Label afterIf = new Label();

                                    mv.visitTryCatchBlock(startTry, endTry, catchBlock, "java/lang/Throwable");

                                    mv.visitLabel(startTry);

                                    for (String argumentName : instrumentationBreakpointInfo.argumentNames) {
                                        if (argumentName.equals("this")/* || argumentName.startsWith("this$")*/) {
                                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                                        }
                                        else {
                                            for (LocalVariableNode localVariable : methodNode.localVariables) {
                                                if (localVariable.name.equals(argumentName) /*&&
                                                start.getOffset() >= localVariable.start.getLabel().getOffset() &&
                                                start.getOffset() < localVariable.end.getLabel().getOffset()*/) {
                                                    Type type = Type.getType(localVariable.desc);
                                                    mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), localVariable.index);
                                                }
                                            }
                                        }
                                    }


                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            fragmentClassName,
                                            conditionCheckMethodName,
                                            instrumentationBreakpointInfo.methodSignature,
                                            false);
                                    mv.visitLabel(endTry);
                                    mv.visitJumpInsn(Opcodes.GOTO, afterIf);

                                    mv.visitLabel(catchBlock);
                                    mv.visitIntInsn(Opcodes.BIPUSH, instrumentationId);
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            getInternalClsName(ConditionalBreakpointTransformer.class),
                                            "instrumentationException",
                                            "(Ljava/lang/Throwable;I)V",
                                            false);

                                    mv.visitLabel(afterIf);
                                } catch (Throwable e) {
                                    throw new InstrumentationBpExceptionWrapper(e, instrumentationId);
                                }
                            }
                        };
                    }
                }, 0, true);
            } catch (Throwable e) {
                if (e instanceof InstrumentationBpExceptionWrapper) {
                    instrumentationFailed(e.getCause(), ((InstrumentationBpExceptionWrapper)e).instrumentationId);
                } else {
                    instrumentationFailed(e, -1);
                }
            }
            return null;
        }
    }

    private static Map<Integer, InstrumentationBreakpointInfo> getLineNumbers(String methodName, Map<String, Map<Integer, InstrumentationBreakpointInfo>> methods) {
        int index = methodName.indexOf("$lambda$");
        if (index > 0) {
            String whereMethodName = methodName.substring(0, index);
            return methods.get(whereMethodName);
        }
        return methods.get(methodName);
    }

    @SuppressWarnings("unused")
    public static void instrumentationFailed(Throwable e, int instrumentationId) {
        // The report will be on the IDE side by a special breakpoint
    }


    @SuppressWarnings("unused")
    public static void instrumentationException(Throwable e, int instrumentationId) {
        // The report will be on the IDE side by a special breakpoint
    }

    private static int extractIdFromFragmentClassName(String fragmentClassName) {
        // Find the last digit sequence in the class name
        int i = fragmentClassName.length() - 1;
        while (i >= 0 && Character.isDigit(fragmentClassName.charAt(i))) {
            i--;
        }
        if (i < fragmentClassName.length() - 1) {
            return Integer.parseInt(fragmentClassName.substring(i + 1));
        }
        return -1; // Return -1 if no number is found
    }

//
//    List<String> lookAtClassNames(String loadingClassName) {
//        if (loadingClassName.contains("$lambda$")) {
//            return
//        }
//    }
}
