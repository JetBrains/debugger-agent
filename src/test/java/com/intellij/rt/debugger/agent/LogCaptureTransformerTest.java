package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.*;
import org.jetbrains.capture.org.objectweb.asm.commons.ClassRemapper;
import org.jetbrains.capture.org.objectweb.asm.commons.SimpleRemapper;
import org.jetbrains.capture.org.objectweb.asm.tree.*;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public class LogCaptureTransformerTest {
    private static final String WRITE_DESCRIPTOR = "([BII)V";
    private static final String TEST_FILE_OUTPUT_STREAM = "test/InstrumentedFileOutputStream";

    @Before
    public void setUp() {
        Assert.assertEquals(
                "Please disable the agent if you try to debug this test. " +
                        "Otherwise, you debug the bundled agent and not the code in the project.",
                this.getClass().getClassLoader(), LogCaptureStorage.class.getClassLoader());
        LogCaptureEncodingTest.resetLogCaptureStorage();
    }

    @Test
    public void insertsCaptureAfterFirstLineNumberInWriteMethod() throws IOException {
        byte[] classBytes = createFileOutputStreamClass(true);
        MethodNode originalWrite = findWriteMethod(classBytes);
        MethodNode write = transformWriteMethod(classBytes);

        int lineNumberIndex = indexOfFirstLineNumber(write);
        int captureIndex = indexOfCaptureCall(write);
        int originalMethodCallIndex = indexOfMethodCall(write, findFirstMethodCall(originalWrite));

        Assert.assertTrue("test class should contain a line number", lineNumberIndex >= 0);
        Assert.assertTrue("capture call should be inserted after the first line number", captureIndex > lineNumberIndex);
        Assert.assertTrue("capture call should still run before the original method body", captureIndex < originalMethodCallIndex);
    }

    @Test
    public void insertsCaptureAtMethodStartWhenWriteMethodHasNoLineNumbers() throws IOException {
        byte[] classBytes = createFileOutputStreamClass(false);
        MethodNode originalWrite = findWriteMethod(classBytes);
        MethodNode write = transformWriteMethod(classBytes);

        int lineNumberIndex = indexOfFirstLineNumber(write);
        int captureIndex = indexOfCaptureCall(write);
        int originalMethodCallIndex = indexOfMethodCall(write, findFirstMethodCall(originalWrite));

        Assert.assertEquals("test class should not contain line numbers", -1, lineNumberIndex);
        Assert.assertTrue("capture call should be inserted", captureIndex >= 0);
        Assert.assertTrue("capture call should run before the original method body", captureIndex < originalMethodCallIndex);
    }

    @Test
    public void instrumentedWriteCapturesStackWithTopFrameLineNumber() throws Exception {
        Properties properties = new Properties();
        properties.put(LogCaptureStorage.BATCHING_ENABLED_PROPERTY, "false");
        LogCaptureStorage.outputWrittenDumpForTests = new ArrayList<>();
        LogCaptureStorage.init(properties, true);

        byte[] loadableClass = prepareFileOutputStreamForTestLoading(readFileOutputStreamClass());
        Class<?> loadedClass = new TestClassLoader().define(loadableClass);

        Constructor<?> constructor = loadedClass.getConstructor(FileDescriptor.class);
        Object instance = constructor.newInstance(FileDescriptor.out);

        Method write = loadedClass.getMethod("write", byte[].class, int.class, int.class);
        String message = "test message";
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        write.invoke(instance, messageBytes, 0, messageBytes.length);

        StackTraceElement topFrame = readCapturedTopFrame(message);
        Assert.assertEquals(TEST_FILE_OUTPUT_STREAM.replace('/', '.'), topFrame.getClassName());
        Assert.assertEquals("write", topFrame.getMethodName());
        Assert.assertTrue("top stack frame should have a real line number: " + topFrame, topFrame.getLineNumber() > 0);
    }

    private static MethodNode transformWriteMethod(byte[] classBytes) {
        byte[] transformed = transformFileOutputStreamClass(classBytes);

        Assert.assertNotNull(transformed);
        return findWriteMethod(transformed);
    }

    private static byte[] transformFileOutputStreamClass(byte[] classBytes) {
        return new LogCaptureTransformer().transform(
                LogCaptureTransformerTest.class.getClassLoader(),
                LogCaptureTransformer.CLASS_NAME,
                null,
                null,
                classBytes
        );
    }

    private static byte[] createFileOutputStreamClass(boolean withLineNumbers) throws IOException {
        byte[] classBytes = readFileOutputStreamClass();
        if (withLineNumbers) {
            return classBytes;
        }
        return removeLineNumbers(classBytes);
    }

    private static byte[] readFileOutputStreamClass() throws IOException {
        InputStream stream = FileOutputStream.class.getResourceAsStream("FileOutputStream.class");
        Assert.assertNotNull("FileOutputStream bytecode should be available", stream);
        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                result.write(buffer, 0, read);
            }
            return result.toByteArray();
        } finally {
            stream.close();
        }
    }

    private static byte[] removeLineNumbers(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.API_VERSION, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(api, methodVisitor) {
                    @Override
                    public void visitLineNumber(int line, Label start) {
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] prepareFileOutputStreamForTestLoading(byte[] classBytes) {
        byte[] transformed = transformFileOutputStreamClass(classBytes);
        ClassReader reader = new ClassReader(transformed);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        // A regular test ClassLoader cannot define classes in java.* packages.
        SimpleRemapper remapper = new SimpleRemapper(Collections.singletonMap(LogCaptureTransformer.CLASS_NAME, TEST_FILE_OUTPUT_STREAM));
        reader.accept(new ClassRemapper(new FileOutputStreamTestClassAdapter(writer), remapper), 0);
        return writer.toByteArray();
    }

    private static class FileOutputStreamTestClassAdapter extends ClassVisitor {

        private FileOutputStreamTestClassAdapter(ClassVisitor classVisitor) {
            super(Opcodes.API_VERSION, classVisitor);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            // Keep the injected capture call, but avoid executing FileOutputStream's JDK-internal write tail.
            if ("write".equals(name) && WRITE_DESCRIPTOR.equals(descriptor)) {
                return new MethodVisitor(api, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        if (opcode == Opcodes.INVOKESTATIC && getInternalLogCaptureStorageName().equals(owner) && "capture".equals(name)) {
                            super.visitInsn(Opcodes.RETURN);
                        }
                    }
                };
            }
            // FileOutputStream's class initializer registers native methods for the original JDK class.
            if ("<clinit>".equals(name)) {
                return null;
            }
            // Replace the real constructor with a minimal one that only sets fd.
            if ("<init>".equals(name) && "(Ljava/io/FileDescriptor;)V".equals(descriptor)) {
                MethodVisitor constructor = super.visitMethod(access, name, descriptor, signature, exceptions);
                constructor.visitCode();
                constructor.visitVarInsn(Opcodes.ALOAD, 0);
                constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/OutputStream", "<init>", "()V", false);
                constructor.visitVarInsn(Opcodes.ALOAD, 0);
                constructor.visitVarInsn(Opcodes.ALOAD, 1);
                constructor.visitFieldInsn(Opcodes.PUTFIELD, TEST_FILE_OUTPUT_STREAM, "fd", "Ljava/io/FileDescriptor;");
                constructor.visitInsn(Opcodes.RETURN);
                constructor.visitMaxs(2, 2);
                constructor.visitEnd();
                return null;
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }

    private static StackTraceElement readCapturedTopFrame(String expectedMsg) throws IOException {
        Assert.assertEquals(1, LogCaptureStorage.outputWrittenDumpForTests.size());
        try (DataInputStream is = LogCaptureEncodingTest.openDump(0)) {
            Assert.assertEquals(1, is.readInt()); // count
            List<StackTraceElement> stack = LogCaptureEncodingTest.readAndCheckStdoutEvent(-1, expectedMsg, is);
            Assert.assertFalse("expected captured stack", stack.isEmpty());
            return stack.get(0);
        }
    }

    private static MethodNode findWriteMethod(byte[] classBytes) {
        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);
        for (MethodNode method : classNode.methods) {
            if ("write".equals(method.name) && WRITE_DESCRIPTOR.equals(method.desc)) {
                return method;
            }
        }
        Assert.fail("write method was not found");
        return null;
    }

    private static int indexOfFirstLineNumber(MethodNode method) {
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof LineNumberNode) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfCaptureCall(MethodNode method) {
        for (int i = 0; i < method.instructions.size(); i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode methodInsnNode = (MethodInsnNode) instruction;
                if (methodInsnNode.getOpcode() == Opcodes.INVOKESTATIC &&
                        getInternalLogCaptureStorageName().equals(methodInsnNode.owner) &&
                        "capture".equals(methodInsnNode.name)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static MethodInsnNode findFirstMethodCall(MethodNode method) {
        for (int i = 0; i < method.instructions.size(); i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            if (instruction instanceof MethodInsnNode) {
                return (MethodInsnNode) instruction;
            }
        }
        Assert.fail("method call was not found");
        return null;
    }

    private static int indexOfMethodCall(MethodNode method, MethodInsnNode expected) {
        for (int i = 0; i < method.instructions.size(); i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode methodInsnNode = (MethodInsnNode) instruction;
                if (methodInsnNode.getOpcode() == expected.getOpcode() &&
                        methodInsnNode.owner.equals(expected.owner) &&
                        methodInsnNode.name.equals(expected.name) &&
                        methodInsnNode.desc.equals(expected.desc)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String getInternalLogCaptureStorageName() {
        return LogCaptureStorage.class.getName().replace('.', '/');
    }

    private static class TestClassLoader extends ClassLoader {
        private Class<?> define(byte[] classBytes) {
            return defineClass(TEST_FILE_OUTPUT_STREAM.replace('/', '.'), classBytes, 0, classBytes.length);
        }
    }
}
