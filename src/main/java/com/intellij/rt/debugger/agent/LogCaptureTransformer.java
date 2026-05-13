package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.ClassVisitor;
import org.jetbrains.capture.org.objectweb.asm.ClassWriter;
import org.jetbrains.capture.org.objectweb.asm.MethodVisitor;
import org.jetbrains.capture.org.objectweb.asm.Opcodes;
import org.jetbrains.capture.org.objectweb.asm.tree.*;

import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;

import static com.intellij.rt.debugger.agent.CaptureAgent.getInternalClsName;

class LogCaptureTransformer implements ClassFileTransformer {
    public static void init(Instrumentation instrumentation) {
        instrumentation.addTransformer(new LogCaptureTransformer(), true);
        for (Class<?> aClass : instrumentation.getAllLoadedClasses()) {
            if (CLASS_NAME.equals(getInternalClsName(aClass))) {
                try {
                    instrumentation.retransformClasses(aClass);
                } catch (UnmodifiableClassException e) {
                    System.err.println("Debugger agent, log capture: cannot retransform class " + aClass.getName());
                    e.printStackTrace(System.err);
                }
            }
        }
    }

    static final String CLASS_NAME = getInternalClsName(FileOutputStream.class);

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (CLASS_NAME.equals(className)) {
            try {
                ClassTransformer transformer = new ClassTransformer(className, classfileBuffer, ClassWriter.COMPUTE_FRAMES, loader);

                return transformer.accept(new ClassVisitor(Opcodes.API_VERSION, transformer.writer) {
                    @Override
                    public MethodVisitor visitMethod(final int access, String name, String descriptor, String signature, String[] exceptions) {
                        final MethodVisitor superMethodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!"write".equals(name)) return superMethodVisitor;

                        // There are also versions like (B)V, but they are uninteresting in terms of logging capture.
                        final boolean isWithOffset;
                        switch (descriptor) {
                            case "([B)V":
                                isWithOffset = false;
                                break;
                            case "([BII)V":
                                isWithOffset = true;
                                break;
                            default:
                                return superMethodVisitor;
                        }

                        return new MethodNode(api, access, name, descriptor, signature, exceptions) {
                            @Override
                            public void visitEnd() {
                                insertCaptureCall(instructions, isWithOffset);
                                accept(superMethodVisitor);
                            }
                        };
                    }
                }, 0, true);
            }
            catch (Exception e) {
                System.err.println("Capture agent: failed to instrument " + className);
                e.printStackTrace(System.err);
            }
        }
        return null;
    }

    private static void insertCaptureCall(InsnList instructions, boolean isWithOffset) {
        InsnList captureCall = createCaptureCall(isWithOffset);
        AbstractInsnNode firstLineNumber = findFirstLineNumber(instructions);
        if (firstLineNumber != null) {
            instructions.insert(firstLineNumber, captureCall);
        } else {
            instructions.insert(captureCall);
        }
    }

    private static AbstractInsnNode findFirstLineNumber(InsnList instructions) {
        for (AbstractInsnNode instruction = instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof LineNumberNode) {
                return instruction;
            }
        }
        return null;
    }

    private static InsnList createCaptureCall(boolean isWithOffset) {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(Opcodes.GETFIELD,
                "java/io/FileOutputStream",
                "fd", "Ljava/io/FileDescriptor;"));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        if (isWithOffset) {
            instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
            instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        }
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                getInternalClsName(LogCaptureStorage.class),
                "capture", "(Ljava/io/FileDescriptor;[B" + (isWithOffset ? "II" : "") + ")V",
                false));
        return instructions;
    }
}
