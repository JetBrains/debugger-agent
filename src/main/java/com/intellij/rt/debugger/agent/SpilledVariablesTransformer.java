package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * This transformer allows to see the value of a spilled local variable after the suspension point (see KT-63720).
 * Fixes the problem of "optimized out local variable".
 *
 * When a visible dead object variable is spilled, Kotlin compiler generates the call to kotlin.coroutines.jvm.internal.SpillingKt#nullOutSpilledVariable,
 * it takes the current value of the variable as an argument and returns null by default.
 * ```
 * fun nullOutSpilledVariable(value: Any?): Any? = null
 * ```
 * [NullOutSpilledVariableTransformer] transforms this function, so that it returns it's argument value, the current value of the variable.
 * ```
 * fun nullOutSpilledVariable(value: Any?): Any? = value
 * ```
 */
public class SpilledVariablesTransformer {
    public static void init(Instrumentation instrumentation) {
        if (Boolean.getBoolean("debugger.agent.enable.coroutines")) {
            instrumentation.addTransformer(new SpillingTransformer(), true);
        }
    }

    private static class SpillingTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(final ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!"kotlin/coroutines/jvm/internal/SpillingKt".equals(className)) {
                return classfileBuffer;
            }
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
                reader.accept(new ClassVisitor(Opcodes.API_VERSION, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor superMethodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (name.equals("nullOutSpilledVariable")) {
                            return new MethodVisitor(Opcodes.API_VERSION, superMethodVisitor) {
                                @Override
                                public void visitCode() {
                                    super.visitCode();
                                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                                    mv.visitInsn(Opcodes.ARETURN);
                                }
                            };
                        }
                        return superMethodVisitor;
                    }
                }, 0);
                byte[] bytes = writer.toByteArray();
                CaptureAgent.storeClassForDebug(className, bytes);
                return bytes;
            } catch (Exception e) {
                System.out.println("SpillingTransformer: failed to instrument " + className);
                e.printStackTrace();
            }
            return null;
        }
    }
}
