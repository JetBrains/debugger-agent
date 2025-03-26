package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.ClassReader;
import org.jetbrains.capture.org.objectweb.asm.ClassVisitor;
import org.jetbrains.capture.org.objectweb.asm.ClassWriter;

public class ClassTransformer {
    private final String className;
    private final ClassReader reader;
    final ClassWriter writer;

    public ClassTransformer(String className, byte[] classfileBuffer, int flags, final ClassLoader loader) {
        this.className = className;
        reader = new ClassReader(classfileBuffer);
        writer = new ClassWriter(reader, flags) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader;
            }
        };
    }

    public byte[] accept(ClassVisitor visitor, int parsingOptions, boolean storeClassForDebug) {
        acceptOriginalByteCode(visitor, parsingOptions);
        return produceModifiedCode(storeClassForDebug);
    }

    public void acceptOriginalByteCode(ClassVisitor visitor, int parsingOptions) {
        reader.accept(visitor, parsingOptions);
    }

    public byte[] produceModifiedCode(boolean storeClassForDebug) {
        byte[] bytes = writer.toByteArray();
        if (storeClassForDebug) {
            CaptureAgent.storeClassForDebug(className, bytes);
        }
        return bytes;
    }
}
