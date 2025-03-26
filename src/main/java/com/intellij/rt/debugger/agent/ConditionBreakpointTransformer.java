package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.*;
import org.jetbrains.capture.org.objectweb.asm.tree.ClassNode;
import org.jetbrains.capture.org.objectweb.asm.tree.LocalVariableNode;
import org.jetbrains.capture.org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.*;

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
    }

    private static class BreakpointInstrumentalist implements ClassFileTransformer {
        @Override
        public byte[] transform(final ClassLoader loader, final String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className.contains("Instrumentation$Generated$Condition")) {
                ClassTransformer transformer = new ClassTransformer(className, classfileBuffer, ClassWriter.COMPUTE_FRAMES, loader);
                ClassVisitor classVisitor = new ClassVisitor(Opcodes.API_VERSION, transformer.writer) {
                };

                transformer.acceptOriginalByteCode(classVisitor, 0);
                MethodVisitor mv = classVisitor.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        breakpointHitMethodName,
                        breakpointHitSignature,
                        null,
                        null
                );
                mv.visitCode();
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();

                return transformer.produceModifiedCode(true);
            }

            final Map<String, Map<Integer, InstrumentationBreakpointInfo>> methods = myBreakpoints.get(className);
            if (methods == null || methods.isEmpty()) {
                return null;
            }

            try {
                final ClassNode classNode = new ClassNode();
                new ClassReader(classfileBuffer).accept(classNode, 0);

                ClassTransformer transformer = new ClassTransformer(className, classfileBuffer, ClassWriter.COMPUTE_FRAMES, loader);

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
                                    addInstrumentationCondition(instrumentationBreakpointInfo, start);
                                }
                                super.visitLineNumber(line, start);

                            }

                            private void addInstrumentationCondition(InstrumentationBreakpointInfo instrumentationBreakpointInfo, Label start) {
                                for (String argumentName : instrumentationBreakpointInfo.argumentNames) {
                                    for (LocalVariableNode localVariable : methodNode.localVariables) {
                                        if (localVariable.name.equals(argumentName) /*&&
                                                start.getOffset() >= localVariable.start.getLabel().getOffset() &&
                                                start.getOffset() < localVariable.end.getLabel().getOffset()*/) {
                                            Type type = Type.getType(localVariable.desc);
                                            mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), localVariable.index);
                                        }
                                    }
                                }

                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        instrumentationBreakpointInfo.fragmentClassName,
                                        "generated_for_debugger_fun",
                                        instrumentationBreakpointInfo.methodSignature,
                                        false);

                                Label skipLabel = new Label();
                                mv.visitJumpInsn(Opcodes.IFEQ, skipLabel);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        instrumentationBreakpointInfo.fragmentClassName,
                                        breakpointHitMethodName,
                                        breakpointHitSignature,
                                        false);
                                mv.visitLabel(skipLabel);
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
}
