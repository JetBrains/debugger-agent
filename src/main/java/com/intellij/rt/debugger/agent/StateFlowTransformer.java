package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

class StateFlowTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(final ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!"kotlinx/coroutines/flow/StateFlowImpl".equals(className)) {
            return classfileBuffer;
        }
        ClassTransformer transformer = new ClassTransformer(className, classfileBuffer, 0, loader);
        // Here, we want to check two things:
        //   - we instrument IJ fork of the coroutine library;
        //   - we only instrument the latest version of the IJ fork (the only stable one).
        // Older versions have two new methods: emitInner and updateInner,
        // the stable one has only updateInner.
        // So, to satisfy the two conditions above, we should check that
        //   - updateInner is present (then isIjFork = true)
        //   - and emitInner is not (then isLatestStableIjFork = true)
        final boolean[] isIjFork = { false };
        final boolean[] isLatestStableIjFork = { true };
        byte[] bytes = transformer.accept(new ClassVisitor(Opcodes.API_VERSION, transformer.writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                switch (name) {
                    case "<init>":
                        return new WrappingTransformer(mv, 1);
                    case "updateInner": {
                        // the method is only present in the IJ version of the coroutines library
                        // (other versions don't have methods the instrumenter adds)
                        isIjFork[0] = true;
                        return new WrappingTransformer(mv, 1);
                    }
                    case "emitInner": {
                        // the method is not present in the latest IJ version of the coroutines library
                        isLatestStableIjFork[0] = false;
                        break;
                    }
                    case "getValue":
                    case "updateState":
                        return new UnwrappingTransformer(mv, new Predicate() {
                            @Override
                            public boolean test(int opcode, String owner, String name, String descriptor) {
                                return isAtomicGet(opcode, owner, name);
                            }
                        });
                    case "collect":
                        return new CompareUnwrappedTransformer(mv);
                }
                return mv;
            }
        }, 0, false);

        boolean shouldTransform = isIjFork[0] && isLatestStableIjFork[0];

        if (!shouldTransform) {
            return classfileBuffer;
        }

        CaptureAgent.storeClassForDebug(className, bytes);
        return bytes;
    }

    private static boolean isAtomicGet(int opcode, String owner, String name) {
        return opcode == Opcodes.INVOKEVIRTUAL
                && "java/util/concurrent/atomic/AtomicReferenceFieldUpdater".equals(owner)
                && "get".equals(name);
    }

    /*
     * Add wrapping to a place when it's necessary. The transformation only replaces the top of the stack by invoking
     * wrapInternal: Object -> Object.
     */
    private static class WrappingTransformer extends MethodVisitor {
        private final int index;

        public WrappingTransformer(MethodVisitor mv, int index) {
            super(Opcodes.API_VERSION, mv);
            this.index = index;
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            super.visitVarInsn(opcode, varIndex);
            if (opcode == Opcodes.ALOAD && varIndex == index) {
                wrapInternal(mv);
            }
        }
    }

    /*
     * Add unwrapping to a place when it's necessary. The transformation only replaces the top of the stack by invoking
     * unwrapInternal: Object -> Object.
     */
    private static class UnwrappingTransformer extends MethodVisitor {

        private final Predicate predicate;

        public UnwrappingTransformer(MethodVisitor mv, Predicate predicate) {
            super(Opcodes.API_VERSION, mv);
            this.predicate = predicate;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (predicate.test(opcode, owner, name, descriptor)) {
                unwrapInternal(mv);
            }
        }
    }

    /*
     * Add unwrapping to the place when it's required to compare unwrapped objects
     * (identity provided by the wrapper and quintessential for async stack traces to work properly should be omitted
     * in certain cases)
     */
    private static class CompareUnwrappedTransformer extends MethodVisitor {

        public CompareUnwrappedTransformer(MethodVisitor mv) {
            super(Opcodes.API_VERSION, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC && "kotlin/jvm/internal/Intrinsics".equals(owner) && "areEqual".equals(name)) {
                // unwrap the top two variables on the stack
                unwrapInternal(mv);
                mv.visitInsn(Opcodes.SWAP);
                unwrapInternal(mv);
                mv.visitInsn(Opcodes.SWAP);
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    private static void wrapInternal(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlinx/coroutines/flow/internal/FlowValueWrapperInternalKt", "wrapInternal", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    private static void unwrapInternal(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlinx/coroutines/flow/internal/FlowValueWrapperInternalKt", "unwrapInternal", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    private interface Predicate {
        boolean test(int opcode, String owner, String name, String descriptor);
    }
}
