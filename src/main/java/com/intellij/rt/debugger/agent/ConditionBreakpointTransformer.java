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

public class ConditionBreakpointTransformer {
    private static final String conditionCheckMethodName = "conditionCheck";
    private static final String breakpointHitMethodName = "breakpointHit";
    private static final String breakpointHitSignature = "()V";


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
        Map<Integer, InstrumentationBreakpointInfo> lineNumbers = methods.get(methodName);
        if (lineNumbers == null) {
            lineNumbers = new LinkedHashMap<>();
            methods.put(methodName, lineNumbers);
        }
        lineNumbers.put(lineNumber, new InstrumentationBreakpointInfo(lineNumber, fragmentClassName, methodSignature, argumentNames));
        System.err.println("V6: addBreakpoint: " + className + "#" + methodName + " at line " + lineNumber + " calling from " + fragmentClassName);
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
                        final Map<Integer, InstrumentationBreakpointInfo> lineNumbers = methods.get(name);
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
                                    System.err.println("Instrumenting!!!: " + instrumentationBreakpointInfo.methodSignature);
                                    addInstrumentationCondition(instrumentationBreakpointInfo, start);
                                }
                                super.visitLineNumber(line, start);

                            }

                            private void addInstrumentationCondition(InstrumentationBreakpointInfo instrumentationBreakpointInfo, Label start) {
                                Label startTry = new Label();
                                Label endTry = new Label();
                                Label catchBlock = new Label();
                                Label afterIf = new Label();

                                mv.visitTryCatchBlock(startTry, endTry, catchBlock, "java/lang/Throwable");

                                mv.visitLabel(startTry);

                                for (String argumentName : instrumentationBreakpointInfo.argumentNames) {
                                    System.err.println("Mega argument: " + argumentName);
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

                                String fragmentClassName = instrumentationBreakpointInfo.fragmentClassName;
                                int instrumentationId = extractIdFromFragmentClassName(fragmentClassName);
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
                                        getInternalClsName(ConditionBreakpointTransformer.class),
                                        "instrumentationException",
                                        "(Ljava/lang/Throwable;I)V",
                                        false);

                                mv.visitLabel(afterIf);
                            }
                        };
                    }
                }, 0, true);
            } catch (Exception e) {
                System.out.println("BreakpointInstrumentalist: failed to instrument " + className);
                e.printStackTrace();
            }
            return null;
        }
    }

    @SuppressWarnings("unused")
    public static void instrumentationException(Throwable e, int instrumentationId) {
        System.err.println("Instrumentation exception from id " + instrumentationId + ":");
        e.printStackTrace();
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
}
