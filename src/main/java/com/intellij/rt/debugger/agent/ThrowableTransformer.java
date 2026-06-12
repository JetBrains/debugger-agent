package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.ClassVisitor;
import org.jetbrains.capture.org.objectweb.asm.FieldVisitor;
import org.jetbrains.capture.org.objectweb.asm.ClassWriter;
import org.jetbrains.capture.org.objectweb.asm.MethodVisitor;
import org.jetbrains.capture.org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Achieves two extra behaviors:
 * <ul>
 *    <li>each created throwable is registered in {@link CaptureStorage#captureThrowable(Throwable)}
 *    to be able to access the async stack trace in {@code printStackTrace} methods via {@link CaptureStorage#getAsyncStackTrace(Throwable)};</li>
 *    <li>the raw VM backtrace is extracted and passed to {@link ThrowableInterner}
 *    through {@link CaptureStorage#captureThrowableBacktrace(Object)}.</li>
 * </ul>
 */
class ThrowableTransformer implements ClassFileTransformer {

    static final String THROWABLE_NAME = CaptureAgent.getInternalClsName(Throwable.class);

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (THROWABLE_NAME.equals(className)) {
            try {
                ClassTransformer transformer = new ClassTransformer(className, classfileBuffer, ClassWriter.COMPUTE_FRAMES, loader);

                return transformer.accept(new ClassVisitor(Opcodes.API_VERSION, transformer.writer) {
                    private String myBacktraceFieldName;
                    private String myBacktraceFieldDescriptor;
                    private boolean myMultipleBacktraceFields;

                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        if (isBacktraceField(name, descriptor)) {
                            if (myBacktraceFieldName == null) {
                                myBacktraceFieldName = name;
                                myBacktraceFieldDescriptor = descriptor;
                            } else {
                                myMultipleBacktraceFields = true;
                                ThrowableInterner.disable("Capture agent: cannot capture Throwable backtrace, ambiguous backtrace fields found");
                            }
                        }
                        return super.visitField(access, name, descriptor, signature, value);
                    }

                    @Override
                    public MethodVisitor visitMethod(final int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor superMethodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                        switch (name) {
                            case "<init>":
                                // Insert extra calls at the end of constructors.
                                return new MethodVisitor(api, superMethodVisitor) {
                                    @Override
                                    public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.RETURN) {
                                            if (myBacktraceFieldName != null && !myMultipleBacktraceFields) {
                                                // Extract backtrace.
                                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                                mv.visitFieldInsn(Opcodes.GETFIELD, THROWABLE_NAME, myBacktraceFieldName, myBacktraceFieldDescriptor);
                                                CaptureAgent.invokeStorageMethod(mv, "captureThrowableBacktrace");
                                            }
                                            else {
                                                ThrowableInterner.disable("Capture agent: cannot capture Throwable backtrace, no supported backtrace field was found before the constructor");
                                            }
                                            // Perform async stack trace capture.
                                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                                            CaptureAgent.invokeStorageMethod(mv, "captureThrowable");
                                        }
                                        super.visitInsn(opcode);
                                    }
                                };

                            case "printStackTrace":
                            case "lockedPrintStackTrace":
                            case "printEnclosedStackTrace":
                                // Replace getOurStackTrace() call with CaptureStorage.getAsyncStackTrace()
                                // during all kinds of stack trace printing.
                                return new MethodVisitor(api, superMethodVisitor) {
                                    @Override
                                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                                        if (name.equals("getOurStackTrace")) {
                                            CaptureAgent.invokeStorageMethod(mv, "getAsyncStackTrace");
                                        } else {
                                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                                        }
                                    }
                                };

                            default:
                                return superMethodVisitor;
                        }
                    }
                }, 0, true);
            }
            catch (Exception e) {
                System.out.println("Capture agent: failed to instrument " + className);
                e.printStackTrace();
            }
        }
        return null;
    }

    private static boolean isBacktraceField(String name, String descriptor) {
        // HotSpot: Object backtrace
        // OpenJ9: Object walkback
        // Moreover, we accept any reference type just to be ready for any variations.
        return ("backtrace".equals(name) || "walkback".equals(name)) &&
                (descriptor.startsWith("L") || descriptor.startsWith("["));
    }
}
