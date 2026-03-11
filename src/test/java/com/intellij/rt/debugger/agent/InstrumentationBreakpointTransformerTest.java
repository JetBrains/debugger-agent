package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.ClassVisitor;
import org.jetbrains.capture.org.objectweb.asm.ClassWriter;
import org.jetbrains.capture.org.objectweb.asm.Label;
import org.jetbrains.capture.org.objectweb.asm.MethodVisitor;
import org.jetbrains.capture.org.objectweb.asm.Opcodes;
import org.jetbrains.capture.org.objectweb.asm.commons.LocalVariablesSorter;
import org.junit.Assert;
import org.junit.Test;

public class InstrumentationBreakpointTransformerTest {
    @Test
    public void breakpointInstrumentationReaderExpandsFramesForLocalVariablesSorter() {
        byte[] classBytes = createClassWithFrames();

        assertFailsWithoutExpandedFrames(classBytes);

        ClassTransformer transformer = new ClassTransformer(
                "test/Sample",
                classBytes,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,
                getClass().getClassLoader()
        );

        byte[] transformed = transformer.accept(
                new LocalVariablesSorterClassVisitor(transformer.writer),
                InstrumentationBreakpointTransformer.BREAKPOINT_TRANSFORM_READER_FLAGS,
                false
        );

        Assert.assertNotNull(transformed);
    }

    private static void assertFailsWithoutExpandedFrames(byte[] classBytes) {
        ClassTransformer transformer = new ClassTransformer(
                "test/Sample",
                classBytes,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS,
                InstrumentationBreakpointTransformerTest.class.getClassLoader()
        );

        try {
            transformer.accept(new LocalVariablesSorterClassVisitor(transformer.writer), 0, false);
            Assert.fail("Expected LocalVariablesSorter to reject non-expanded frames");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("LocalVariablesSorter only accepts expanded frames"));
        }
    }

    private static byte[] createClassWithFrames() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_7, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/Sample", null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "branch", "(Z)I", null, null);
        method.visitCode();
        Label elseLabel = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, elseLabel);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(elseLabel);
        method.visitInsn(Opcodes.ICONST_2);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static class LocalVariablesSorterClassVisitor extends ClassVisitor {
        private LocalVariablesSorterClassVisitor(ClassVisitor classVisitor) {
            super(Opcodes.API_VERSION, classVisitor);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!"branch".equals(name)) {
                return methodVisitor;
            }
            return new LocalVariablesSorter(api, access, descriptor, methodVisitor) {
            };
        }
    }
}
