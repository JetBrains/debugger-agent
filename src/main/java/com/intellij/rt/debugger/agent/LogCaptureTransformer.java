package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.ClassVisitor;
import org.jetbrains.capture.org.objectweb.asm.ClassWriter;
import org.jetbrains.capture.org.objectweb.asm.MethodVisitor;
import org.jetbrains.capture.org.objectweb.asm.Opcodes;

import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;

import static com.intellij.rt.debugger.agent.CaptureAgent.getInternalClsName;

class LogCaptureTransformer implements ClassFileTransformer {
    public static void init(Instrumentation instrumentation) {
        if (!Boolean.getBoolean("debugger.agent.enable.log.capture")) return;

        if (!LogCaptureStorage.init()) return;

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
                        MethodVisitor superMethodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
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

                        return new MethodVisitor(api, superMethodVisitor) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitFieldInsn(Opcodes.GETFIELD,
                                        "java/io/FileOutputStream",
                                        "fd", "Ljava/io/FileDescriptor;");
                                mv.visitVarInsn(Opcodes.ALOAD, 1);
                                if (isWithOffset) {
                                    mv.visitVarInsn(Opcodes.ILOAD, 2);
                                    mv.visitVarInsn(Opcodes.ILOAD, 3);
                                }
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        getInternalClsName(LogCaptureStorage.class),
                                        "capture", "(Ljava/io/FileDescriptor;[B" + (isWithOffset ? "II" : "") + ")V",
                                        false);
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
}
