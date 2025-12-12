package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * This transformer allows to see the optimized tail-call continuations in the stack trace when the debugger is attached.
 *
 * TailCallAsyncStackTraceEntryKt#wrapContinuation function is replaced with wrapContinuationReal,
 * which wraps a continuation with another continuation, so async stack trace does not have gaps because of tail-call functions.
 *
 * See KT-74051
 */
public class TailCallContinuationTransformer {
    public static void init(Instrumentation instrumentation) {
        if (Boolean.getBoolean("debugger.agent.enable.coroutines")) {
            instrumentation.addTransformer(new TailCallTransformer(), true);
        }
    }

    private static class TailCallTransformer implements ClassFileTransformer {
        private static final String WRAP_CONTINUATION_REAL_DESC = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I[Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Lkotlin/coroutines/Continuation;";

        @Override
        public byte[] transform(final ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!"kotlin/coroutines/jvm/internal/TailCallAsyncStackTraceEntryKt".equals(className)) {
                return classfileBuffer;
            }
            try {
                ClassTransformer transformer = new ClassTransformer(className, classfileBuffer, ClassWriter.COMPUTE_FRAMES, loader);
                final boolean[] wrapContinuationRealIsPresent = { false };
                byte[] bytes = transformer.accept(new ClassVisitor(Opcodes.API_VERSION, transformer.writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        switch (name) {
                            case "wrapContinuationReal":
                                if (descriptor.equals(WRAP_CONTINUATION_REAL_DESC)) {
                                    wrapContinuationRealIsPresent[0] = true;
                                    break;
                                }
                            case "wrapContinuation":
                                int argumentCount = Type.getArgumentCount(descriptor);
                                if (argumentCount != 6) {
                                    System.err.println("TailCallContinuationTransformer: expected 6 arguments in TailCallAsyncStackTraceEntryKt#wrapContinuation, but got " + argumentCount + ", descriptor = " + descriptor);
                                    break;
                                }
                                return new MethodVisitor(api, mv) {
                                    @Override
                                    public void visitCode() {
                                        super.visitCode();

                                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                                        mv.visitVarInsn(Opcodes.ALOAD, 2);
                                        mv.visitVarInsn(Opcodes.ILOAD, 3);
                                        mv.visitVarInsn(Opcodes.ALOAD, 4);
                                        mv.visitVarInsn(Opcodes.ALOAD, 5);

                                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlin/coroutines/jvm/internal/TailCallAsyncStackTraceEntryKt", "wrapContinuationReal", WRAP_CONTINUATION_REAL_DESC, false);
                                        mv.visitInsn(Opcodes.ARETURN);
                                        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                                        mv.visitMaxs(2, 6);
                                        mv.visitEnd();
                                    }
                                };
                        }
                        return mv;
                    }
                }, 0, false);

                if (!wrapContinuationRealIsPresent[0]) {
                    return classfileBuffer;
                }

                CaptureAgent.storeClassForDebug(className, bytes);
                return bytes;
            } catch (Exception e) {
                System.out.println("TailCallContinuationTransformer: failed to instrument " + className);
                e.printStackTrace();
            }
            return null;
        }
    }
}
