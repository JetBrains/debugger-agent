package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import static org.jetbrains.capture.org.objectweb.asm.Opcodes.F_SAME;

class SharedFlowTransformer implements ClassFileTransformer {
    private final boolean strictDoubleWrappingCheck;

    public SharedFlowTransformer(boolean strictDoubleWrappingCheck) {
        this.strictDoubleWrappingCheck = strictDoubleWrappingCheck;
    }

    @Override
    public byte[] transform(final ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!"kotlinx/coroutines/flow/internal/FlowValueWrapperInternalKt".equals(className)) {
            return classfileBuffer;
        }
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.API_VERSION, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                switch (name) {
                    case "wrapInternal":
                        return new WrapMethodTransformer(mv, strictDoubleWrappingCheck);
                    case "unwrapInternal":
                        return new UnwrapMethodTransformer(mv);
                }
                return mv;
            }
        }, 0);
        byte[] bytes = writer.toByteArray();
        CaptureAgent.storeClassForDebug(className, bytes);
        return bytes;
    }

    /*
        Default wrapInternal is:
        ```
          internal fun <T> wrapInternal(value: T): T = value
        ```

        which compiles to bytecode
        ```
          aload_0
          areturn
        ```

        The transformed code should call the function
        ```
          private fun wrapInternalDebuggerCapture(value: Any?): Any = FlowValueWrapperInternal(value)
        ```

        so the transformation should yield the bytecode
        ```
           0: aload_0
           1: invokestatic  #10                 // Method wrapInternalDebuggerCapture:(Ljava/lang/Object;)Ljava/lang/Object;
           4: areturn
        ```
     */
    private static class WrapMethodTransformer extends MethodVisitor {
        private final boolean strictDoubleWrappingCheck;

        public WrapMethodTransformer(MethodVisitor mv, boolean strictDoubleWrappingCheck) {
            super(Opcodes.API_VERSION, mv);
            this.strictDoubleWrappingCheck = strictDoubleWrappingCheck;
        }

        @Override
        public void visitCode() {
            super.visitCode();

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            String prefix;
            if (strictDoubleWrappingCheck) {
                prefix = "Strict";
            }
            else {
                prefix = "Lenient";
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlinx/coroutines/flow/internal/FlowValueWrapperInternalKt", "wrapInternalDebuggerCapture" + prefix, "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
    }

    /*
        Default unwrap is:
        ```
          internal fun <T> unwrapInternal(value: T): T = value
        ```

        which compiles to bytecode
        ```
          aload_0
          areturn
        ```

        The transformed code should call the function
        ```
          private fun unwrapInternalDebuggerCapture(value: Any?): Any? = (value as? FlowValueWrapperInternal<*>)?.value ?: value
        ```

        so the transformation should yield the bytecode
        ```
           0: aload_0
           1: invokestatic  #16                 // Method unwrapInternalDebuggerCapture:(Ljava/lang/Object;)Ljava/lang/Object;
           4: areturn
        ```
     */
    private static class UnwrapMethodTransformer extends MethodVisitor {
        public UnwrapMethodTransformer(MethodVisitor mv) {
            super(Opcodes.API_VERSION, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlinx/coroutines/flow/internal/FlowValueWrapperInternalKt", "unwrapInternalDebuggerCapture", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
    }
}
