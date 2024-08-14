package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

class SharedFlowTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(final ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!"kotlinx/coroutines/flow/SharedFlowImpl".equals(className)) {
            return classfileBuffer;
        }
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader;
            }
        };
        reader.accept(new ClassVisitor(Opcodes.API_VERSION, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                switch (name) {
                    case "wrap":
                        return new WrapMethodTransformer(mv);
                    case "unwrap":
                        return new UnwrapMethodTransformer(mv);
                }
                return mv;
            }
        }, 0);
        return writer.toByteArray();
    }

    /*
        Default wrap is:
        ```
          private fun <T> wrap(value: T): T = value
        ```

        which compiles to bytecode
        ```
          aload_1
          areturn
        ```

        The transformed code should call the function
        ```
          private fun wrapDebuggerCapture(value: Any?) = FlowValueWrapper(value)
        ```

        so the transformation should yield the bytecode
        ```
           0: aload_0
           1: aload_1
           2: invokespecial #530                // Method wrapDebuggerCapture:(Ljava/lang/Object;)Ljava/lang/Object;
           5: areturn
        ```
     */
    private static class WrapMethodTransformer extends MethodVisitor {
        public WrapMethodTransformer(MethodVisitor mv) {
            super(Opcodes.API_VERSION, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "kotlinx/coroutines/flow/SharedFlowImpl", "wrapDebuggerCapture", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitInsn(Opcodes.ARETURN);
        }
    }

    /*
        Default unwrap is:
        ```
          private fun <T> unwrap(value: T): T = value
        ```

        which compiles to bytecode
        ```
          aload_1
          areturn
        ```

        The transformed code should call the function
        ```
          private fun unwrapDebuggerCapture(value: Any?): Any? = (value as? FlowValueWrapper<*>)?.value ?: value
        ```

        so the transformation should yield the bytecode
        ```
           0: aload_0
           1: aload_1
           2: invokespecial #530                // Method unwrapDebuggerCapture:(Ljava/lang/Object;)Ljava/lang/Object;
           5: areturn
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
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "kotlinx/coroutines/flow/SharedFlowImpl", "unwrapDebuggerCapture", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitInsn(Opcodes.ARETURN);
        }
    }
}
