package com.intellij.rt.debugger.agent;

import org.jetbrains.capture.org.objectweb.asm.*;
import org.jetbrains.capture.org.objectweb.asm.commons.LocalVariablesSorter;
import org.jetbrains.capture.org.objectweb.asm.tree.*;
import org.jetbrains.capture.org.objectweb.asm.tree.analysis.Analyzer;
import org.jetbrains.capture.org.objectweb.asm.tree.analysis.BasicValue;
import org.jetbrains.capture.org.objectweb.asm.tree.analysis.BasicVerifier;
import org.jetbrains.capture.org.objectweb.asm.tree.analysis.Frame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.*;

import static com.intellij.rt.debugger.agent.CaptureAgent.getInternalClsName;

class InstrumentationBpExceptionWrapper extends RuntimeException {
    final int instrumentationId;

    InstrumentationBpExceptionWrapper(Throwable e, int instrumentationId) {
        super(e);
        this.instrumentationId = instrumentationId;
    }
}

class InstrumentationBreakpointInfo {
    final int instrumentationId;
    final int lineNumber;
    final String fragmentClassName;
    final String fragmentEntryMethodName;
    final String methodSignature;
    final List<String> argumentNames;
    Type[] stackTypes = new Type[0];

    InstrumentationBreakpointInfo(int instrumentationId, int lineNumber, String fragmentClassName, String fragmentEntryMethodName, String methodSignature, List<String> argumentNames) {
        this.instrumentationId = instrumentationId;
        this.lineNumber = lineNumber;
        this.fragmentClassName = fragmentClassName;
        this.fragmentEntryMethodName = fragmentEntryMethodName;
        this.methodSignature = methodSignature;
        this.argumentNames = argumentNames;
    }
}


interface ArgumentGetter {
    void generateArgumentLoad(MethodVisitor mv);
}

class InstrumentationBreakpointMappingInfo {
    final InstrumentationBreakpointInfo inputInfo;
    final List<ArgumentGetter> argumentLoadGenerators;

    InstrumentationBreakpointMappingInfo(InstrumentationBreakpointInfo inputInfo, List<ArgumentGetter> argumentLoadGenerators) {
        this.inputInfo = inputInfo;
        this.argumentLoadGenerators = argumentLoadGenerators;
    }
}

public class InstrumentationBreakpointTransformer {
    static final int BREAKPOINT_TRANSFORM_READER_FLAGS = ClassReader.EXPAND_FRAMES;
    private static final Set<String> myClassesWithBreakpoints = new LinkedHashSet<>();

    public static void init(Properties properties, Instrumentation instrumentation) {
        applyProperties(properties);
        instrumentation.addTransformer(new BreakpointInstrumentalist(), true);
    }

    private static void applyProperties(Properties properties) {
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            addPoint((String)entry.getKey(), (String)entry.getValue());
        }
    }

    private static void addPoint(String propertyKey, String propertyValue) {
        if (propertyKey.startsWith("instrumentation.breakpoint.class")) {
            addBreakpointClass(propertyValue);
        }
    }

    public static void addBreakpointClass(String className) {
        myClassesWithBreakpoints.add(className);
    }

    private static class BreakpointInstrumentalist implements ClassFileTransformer {
        @Override
        public byte[] transform(final ClassLoader loader, final String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!myClassesWithBreakpoints.contains(className)) {
                return null;
            }

            final List<Integer> successIds = new ArrayList<>();

            final byte[] resultBytecode;
            try {
                String[][] infoTable = requestInstrumentedInfo(className);
                if (infoTable.length == 0) {
                    return null;
                }

                final Map<String, Map<Integer, InstrumentationBreakpointInfo>> methods = new LinkedHashMap<>();
                for (String[] infoArray : infoTable) {
                    List<String> info = Arrays.asList(infoArray);

                    String className2 = info.get(0);
                    if (!className2.equals(className)) {
                        // TODO: ERROR here
                        continue;
                    }
                    String methodName = info.get(1);
                    int lineNumber = Integer.parseInt(info.get(2));
                    String fragmentClassName = info.get(3);
                    String fragmentEntryMethodName = info.get(4);
                    String methodSignature = info.get(5);
                    List<String> argumentNames = info.subList(6, info.size());

                    Map<Integer, InstrumentationBreakpointInfo> lineNumbers = getLineNumbers(methodName, methods);
                    if (lineNumbers == null) {
                        lineNumbers = new LinkedHashMap<>();
                        methods.put(methodName, lineNumbers);
                    }

                    int instrumentationId = extractIdFromFragmentClassName(fragmentClassName);
                    lineNumbers.put(lineNumber, new InstrumentationBreakpointInfo(instrumentationId, lineNumber, fragmentClassName, fragmentEntryMethodName, methodSignature, argumentNames));
                }

                if (methods.isEmpty()) {
                    return null;
                }

                final ClassNode classNode = new ClassNode();
                new ClassReader(classfileBuffer).accept(classNode, 0);

                ClassTransformer transformer = new ClassTransformer(
                        className, classfileBuffer, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader
                );


                resultBytecode = transformer.accept(new ClassVisitor(Opcodes.API_VERSION, transformer.writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor superMethodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                        final Map<Integer, InstrumentationBreakpointInfo> lineNumbers = getLineNumbers(name, methods);
                        if (lineNumbers == null || lineNumbers.isEmpty()) {
                            return superMethodVisitor;
                        }

                        MethodNode m = null;
                        for (MethodNode method : classNode.methods) {
                            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                                m = method;
                            }
                        }
                        if (m == null) {
                            return superMethodVisitor;
                        }

                        final Map<Integer, InstrumentationBreakpointMappingInfo> argumentMapping = collectArgumentMapping(classNode.name, m, lineNumbers);

                        if (argumentMapping.isEmpty()) {
                            return superMethodVisitor;
                        }

                        return new LocalVariablesSorter(api, access, descriptor, superMethodVisitor) {

                            @Override
                            public void visitLineNumber(int line, Label start) {
                                InstrumentationBreakpointMappingInfo instrumentationBreakpointInfo = argumentMapping.get(line);
                                if (instrumentationBreakpointInfo != null) {
                                    // Set only one breakpoint per line
                                    argumentMapping.remove(line);
                                    addBpInstrumentation(instrumentationBreakpointInfo);
                                }
                                super.visitLineNumber(line, start);
                            }

                            private void addBpInstrumentation(InstrumentationBreakpointMappingInfo argumentMapping) {
                                String fragmentClassName = argumentMapping.inputInfo.fragmentClassName;
                                String fragmentEntryMethodName = argumentMapping.inputInfo.fragmentEntryMethodName;
                                int instrumentationId = argumentMapping.inputInfo.instrumentationId;
                                Type[] stackTypes = argumentMapping.inputInfo.stackTypes;

                                successIds.add(instrumentationId);

                                try {
                                    Label startTry = new Label();
                                    Label endTry = new Label();
                                    Label catchBlock = new Label();
                                    Label endOfTry = new Label();

                                    //                 spill the variables
                                    // startTry  :     try {
                                    //                     fragmentClassName.fragmentEntryMethodName(arguments)
                                    // catchBlock:     } catch (Throwable e) {
                                    //                     reportIncorrectInstrumentation(e.toString(), id)
                                    //                 }
                                    // endOfTry :
                                    //                 restore the variables
                                    // theEnd:

                                    String theTransformerClassName = getInternalClsName(InstrumentationBreakpointTransformer.class);

                                    int[] stackLocalIndexes = spillStack(stackTypes);

                                    mv.visitTryCatchBlock(startTry, endTry, catchBlock, "java/lang/Throwable");

                                    mv.visitLabel(startTry);

                                    for (ArgumentGetter argumentLoadGenerator : argumentMapping.argumentLoadGenerators) {
                                        argumentLoadGenerator.generateArgumentLoad(mv);
                                    }

                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            fragmentClassName,
                                            fragmentEntryMethodName,
                                            argumentMapping.inputInfo.methodSignature,
                                            false);
                                    mv.visitLabel(endTry);
                                    mv.visitJumpInsn(Opcodes.GOTO, endOfTry);

                                    mv.visitLabel(catchBlock);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                            "java/lang/Throwable",
                                            "toString",
                                            "()Ljava/lang/String;",
                                            false);
                                    if (instrumentationId >= Short.MAX_VALUE) {
                                        throw new IllegalArgumentException("Instrumentation ID exceeds short range: " + instrumentationId);
                                    }
                                    mv.visitIntInsn(Opcodes.SIPUSH, instrumentationId);
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            theTransformerClassName,
                                            "reportIncorrectInstrumentation",
                                            "(Ljava/lang/String;I)V",
                                            false);

                                    mv.visitLabel(endOfTry);
                                    restoreStack(stackTypes, stackLocalIndexes);
                                } catch (Throwable e) {
                                    throw new InstrumentationBpExceptionWrapper(e, instrumentationId);
                                }
                            }

                            private int[] spillStack(Type[] stackTypes) {
                                int[] stackLocalIndexes = new int[stackTypes.length];
                                for (int i = 0; i < stackTypes.length; i++) {
                                    stackLocalIndexes[i] = newLocal(stackTypes[i]);
                                }
                                for (int i = stackTypes.length - 1; i >= 0; i--) {
                                    mv.visitVarInsn(stackTypes[i].getOpcode(Opcodes.ISTORE), stackLocalIndexes[i]);
                                }
                                return stackLocalIndexes;
                            }

                            private void restoreStack(Type[] stackTypes, int[] stackLocalIndexes) {
                                for (int i = 0; i < stackTypes.length; i++) {
                                    mv.visitVarInsn(stackTypes[i].getOpcode(Opcodes.ILOAD), stackLocalIndexes[i]);
                                }
                            }
                        };
                    }
                }, BREAKPOINT_TRANSFORM_READER_FLAGS, true);
            } catch (Throwable e) {
                if (e instanceof InstrumentationBpExceptionWrapper) {
                    instrumentationFailed(e.getCause(), new int[]{((InstrumentationBpExceptionWrapper) e).instrumentationId}, null);
                } else {
                    instrumentationFailed(e, getIntArrayFromList(successIds), null);
                }
                return null;
            }

            // Verify the result bytecode
            try {
                ClassReader cr = new ClassReader(resultBytecode);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);

                for (MethodNode mn : cn.methods) {
                    // use Basic verifier to not load any class
                    Analyzer<BasicValue> a = new Analyzer<>(new BasicVerifier());
                    a.analyze(cn.name, mn);
                }
            } catch (Throwable e) {
                instrumentationFailed(e, getIntArrayFromList(successIds), resultBytecode);
                return null;
            }

            int[] intArray = getIntArrayFromList(successIds);

            successfullyInstrumented(loader, intArray);

            return resultBytecode;
        }
    }

    private static Map<Integer, InstrumentationBreakpointMappingInfo> collectArgumentMapping(String owner, MethodNode method, Map<Integer, InstrumentationBreakpointInfo> lineNumbers) {
        final Map<Integer, InstrumentationBreakpointMappingInfo> remappingInfo = new HashMap<>();
        Frame<BasicValue>[] frames = analyzeMethodFrames(owner, method);

        Map<Integer, Integer> visitedLineNumbers = new HashMap<>();

        int lastControlInstructionIndex = -1;

        for (int instructionIndex = 0; instructionIndex < method.instructions.size(); instructionIndex++) {
            AbstractInsnNode instruction = method.instructions.get(instructionIndex);

            int opcode = instruction.getOpcode();
            if (instruction instanceof JumpInsnNode ||
                instruction instanceof TableSwitchInsnNode ||
                instruction instanceof LookupSwitchInsnNode ||
                opcode == Opcodes.RET ||
                opcode == Opcodes.ATHROW ||
                (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)
            ) {
                lastControlInstructionIndex = instructionIndex;
            }


            if (!(instruction instanceof LineNumberNode)) {
                continue;
            }

            int lineNumber = ((LineNumberNode)instruction).line;
            InstrumentationBreakpointInfo instrumentationBreakpointInfo = lineNumbers.get(lineNumber);
            if (instrumentationBreakpointInfo == null) {
                continue;
            }
            try {
                // skip non-trivial cases for now
                Integer previous = visitedLineNumbers.put(lineNumber, instructionIndex);
                if (previous != null && previous < lastControlInstructionIndex) {
                    impossibleToInstrument("Several instructions marked with the same line " + lineNumber, instrumentationBreakpointInfo.instrumentationId);
                    remappingInfo.remove(lineNumber);
                }
                else {
                    Type[] stackTypes = collectStackTypes(method, frames, instructionIndex, instrumentationBreakpointInfo);
                    if (stackTypes == null) {
                        remappingInfo.remove(lineNumber);
                        continue;
                    }
                    instrumentationBreakpointInfo.stackTypes = stackTypes;

                    List<ArgumentGetter> argumentLoadGenerators = new ArrayList<>();
                    for (String argumentName : instrumentationBreakpointInfo.argumentNames) {
                        boolean isFound = false;
                        for (LocalVariableNode localVariableNode : method.localVariables) {
                            if (localVariableNode.name.equals(argumentName) &&
                                    method.instructions.indexOf(localVariableNode.start) <= instructionIndex &&
                                    instructionIndex < method.instructions.indexOf(localVariableNode.end)
                            ) {
                                isFound = true;

                                final Type type = Type.getType(localVariableNode.desc);
                                final int localVariableIndex = localVariableNode.index;
                                argumentLoadGenerators.add(new ArgumentGetter() {
                                    @Override
                                    public void generateArgumentLoad(MethodVisitor mv) {
                                        mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), localVariableIndex);
                                    }
                                });
                                break;
                            }
                        }
                        if (!isFound) {
                            impossibleToInstrument("Argument " + argumentName  + " not found", instrumentationBreakpointInfo.instrumentationId);
                            break;
                        }
                    }

                    if (argumentLoadGenerators.size() == instrumentationBreakpointInfo.argumentNames.size()) {
                        remappingInfo.put(lineNumber, new InstrumentationBreakpointMappingInfo(instrumentationBreakpointInfo, argumentLoadGenerators));
                    }
                }
            } catch (Throwable e) {
                throw new InstrumentationBpExceptionWrapper(e, instrumentationBreakpointInfo.instrumentationId);
            }
        }

        return remappingInfo;
    }

    private static Frame<BasicValue>[] analyzeMethodFrames(String owner, MethodNode method) {
        Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicVerifier());
        try {
            return analyzer.analyze(owner, method);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Type[] collectStackTypes(MethodNode method, Frame<BasicValue>[] frames, int instructionIndex, InstrumentationBreakpointInfo instrumentationBreakpointInfo) {
        Frame<BasicValue> frame = getFrameForInstrumentation(method, frames, instructionIndex);
        if (frame == null) {
            impossibleToInstrument("Cannot determine operand stack at line " + instrumentationBreakpointInfo.lineNumber, instrumentationBreakpointInfo.instrumentationId);
            return null;
        }

        Type[] stackTypes = new Type[frame.getStackSize()];
        for (int i = 0; i < stackTypes.length; i++) {
            try {
                stackTypes[i] = getSpillType(frame.getStack(i));
            } catch (IllegalArgumentException e) {
                impossibleToInstrument(e.getMessage(), instrumentationBreakpointInfo.instrumentationId);
                return null;
            }
        }
        return stackTypes;
    }

    private static Frame<BasicValue> getFrameForInstrumentation(MethodNode method, Frame<BasicValue>[] frames, int instructionIndex) {
        Frame<BasicValue> frame = frames[instructionIndex];
        if (frame != null) {
            return frame;
        }

        for (int i = instructionIndex + 1; i < method.instructions.size(); i++) {
            if (method.instructions.get(i).getOpcode() >= 0) {
                return frames[i];
            }
        }

        return null;
    }

    private static Type getSpillType(BasicValue value) {
        Type type = value.getType();
        if (type == null) {
            return Type.getObjectType("java/lang/Object");
        }

        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                return Type.INT_TYPE;
            case Type.FLOAT:
                return Type.FLOAT_TYPE;
            case Type.LONG:
                return Type.LONG_TYPE;
            case Type.DOUBLE:
                return Type.DOUBLE_TYPE;
            case Type.ARRAY:
            case Type.OBJECT:
                return type;
            default:
                throw new IllegalArgumentException("Unsupported operand stack value");
        }
    }

    private static Map<Integer, InstrumentationBreakpointInfo> getLineNumbers(String methodName, Map<String, Map<Integer, InstrumentationBreakpointInfo>> methods) {
        int index = methodName.indexOf("$lambda$");
        if (index > 0) {
            String whereMethodName = methodName.substring(0, index);
            return methods.get(whereMethodName);
        }

        if (methodName.startsWith("lambda$")) {
            String rest = methodName.substring(7);
            int i = rest.indexOf("$");
            if (i > 0) {
                String whereMethodName = rest.substring(0, i);
                return methods.get(whereMethodName);
            }
        }

        return methods.get(methodName);
    }

    private static final ThreadLocal<Integer> myThreadLocal = new ThreadLocal<>();

    /**
     * This method is used from the Debugger Engine side to check
     * that the triggered breakpoint is not met under some evaluation
     */
    @SuppressWarnings("unused")
    public static boolean isUnderBreakpointCheck() {
        Integer previous = myThreadLocal.get();
        if (previous == null) {
            previous = 0;
        }
        return previous > 0;
    }

    /**
     * This method is used from instrumented code
     *
     * @return true if the breakpoint condition/logging should be skipped
     */
    @SuppressWarnings("unused")
    public static boolean enterBreakpointCheckInternal() {
        if (!isUnmutedState) {
            return true;
        }

        return enterBreakpointCheck();
    }

    /**
     * This method is used from instrumented code and from IDEA's debugger.
     *
     * @return true if the thread is in progress of condition/logging evaluation, so other breakpoints should be skipped.
     */
    public static boolean enterBreakpointCheck() {
        Integer previous = myThreadLocal.get();
        if (previous == null) {
            previous = 0;
        }
        if (previous > 0) {
            return true;
        }
        myThreadLocal.set(previous + 1);

        return false;
    }

    /** This method is used from instrumented code */
    @SuppressWarnings("unused")
    public static void checkIsDone() {
        Integer previous = myThreadLocal.get();
        if (previous == null) {
            previous = 0;
        }
        myThreadLocal.set(previous - 1);
    }

    /**
     * This field is changing by the Debugger Engine side and used inside instrumented code
     * <p>
     * Should not be initialized, so we can set it from Class Prepare Event from the Debugger Engine,
     * and the `init` will not rewrite it
     */
    @SuppressWarnings("unused")
    public static boolean isUnmutedState;

    public static void instrumentationFailed(Throwable exception, int[] instrumentationIds, byte[] resultBytecode) {
        try (
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(baos);
        ) {
            ps.println(exception);

            StackTraceElement[] trace = exception.getStackTrace();
            int lastToReportIndex = -1;
            for (int i = 0; i < trace.length; i++) {
                if (CaptureStorage.isAgentFrame(trace[i])) {
                    lastToReportIndex = i;
                }
            }
            for (int i = 0; i <= lastToReportIndex; i++) {
                ps.println("\tat " + trace[i]);
            }

            reportInstrumentationFailed(baos.toString(), instrumentationIds, resultBytecode);
        } catch (IOException ex) {
            // Should not happen.
            reportInstrumentationFailed(exception.toString(), instrumentationIds, resultBytecode);
        }
    }

    public static String[][] requestInstrumentedInfo(@SuppressWarnings("unused") String className) {
        return new String[0][];
    }

    @SuppressWarnings("unused")
    public static void successfullyInstrumented(ClassLoader loader, int[] instrumentationIds) {
        // The report will be on the IDE side by a special breakpoint
    }

    @SuppressWarnings("unused")
    public static void reportInstrumentationFailed(String report, int[] instrumentationIds, byte[] resultBytecode) {
        // The report will be on the IDE side by a special breakpoint
    }

    @SuppressWarnings("unused")
    public static void reportIncorrectInstrumentation(String report, int instrumentationId) {
        // The report will be on the IDE side by a special breakpoint
    }

    @SuppressWarnings("unused")
    public static void impossibleToInstrument(String message, int instrumentationId) {
        // The report will be on the IDE side by a special breakpoint
    }

    private static int extractIdFromFragmentClassName(String fragmentClassName) {
        // Find the last digit sequence in the class name
        int i = fragmentClassName.length() - 1;
        while (i >= 0 && Character.isDigit(fragmentClassName.charAt(i))) {
            i--;
        }
        if (i < fragmentClassName.length() - 1) {
            return Integer.parseInt(fragmentClassName.substring(i + 1));
        }
        throw new IllegalArgumentException("Fragment class name does not contain a valid instrumentation ID: " + fragmentClassName);
    }

    private static int[] getIntArrayFromList(List<Integer> successIds) {
        int[] intArray = new int[successIds.size()];
        for (int i = 0; i < successIds.size(); i++) {
            intArray[i] = successIds.get(i);
        }
        return intArray;
    }
}
