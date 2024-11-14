package name.remal.gradle_plugins.insert_null_checks;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.Files.write;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static name.remal.gradle_plugins.insert_null_checks.BytecodeTestUtils.wrapWithTestClassVisitors;
import static name.remal.gradle_plugins.toolkit.InTestFlags.isInUnitTest;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_ENUM;
import static org.objectweb.asm.Opcodes.ACC_MANDATED;
import static org.objectweb.asm.Opcodes.ACC_MODULE;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Type.getArgumentTypes;
import static org.objectweb.asm.Type.getDescriptor;
import static org.objectweb.asm.Type.getReturnType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import lombok.CustomLog;
import lombok.SneakyThrows;
import lombok.experimental.SuperBuilder;
import lombok.val;
import org.jetbrains.annotations.VisibleForTesting;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

@SuperBuilder
@CustomLog
class ClassFileProcessor extends ClassFileProcessorParams {

    private static final boolean IN_TEST = isInUnitTest();

    @SuppressWarnings("java:S5803")
    private static final String INSERT_NULL_CHECKS_IN_TESTS_ONLY_DESC =
        getDescriptor(InsertNullChecksInTestsOnly.class);

    private static final String GENERATED_DESC_SUFFIX = "/Generated;";


    private final Set<String> exclusionAnnotationDescs = exclusionAnnotationClassNames.stream()
        .map(ClassFileProcessor::classNameToDescription)
        .collect(toCollection(LinkedHashSet::new));

    private final Set<String> validationAnnotationDescs = validationAnnotationClassNames.stream()
        .map(ClassFileProcessor::classNameToDescription)
        .collect(toCollection(LinkedHashSet::new));

    private final List<String> validationAnnotationDescPrefixes = validationAnnotationBasePackages.stream()
        .map(pkg -> classNameToDescription(pkg + '.').replace(";", ""))
        .collect(toList());

    private final Set<String> nonNullAnnotationDescs = nonNullAnnotationClassNames.stream()
        .map(ClassFileProcessor::classNameToDescription)
        .collect(toCollection(LinkedHashSet::new));

    private final List<String> nullableAnnotationDescs = nullableAnnotationSimpleClassNames.stream()
        .map(name -> '/' + name + 'L')
        .collect(toList());


    private boolean changed;

    @SneakyThrows
    @SuppressWarnings("java:S3776")
    public void process() {
        val classNode = new ClassNode();
        ClassReader classReader;
        try (val inputStream = newInputStream(sourcePath)) {
            classReader = new ClassReader(inputStream);
            classReader.accept(classNode, 0);
        }

        if (classNode.invisibleAnnotations != null && !IN_TEST) {
            for (val annotation : classNode.invisibleAnnotations) {
                if (annotation.desc.equals(INSERT_NULL_CHECKS_IN_TESTS_ONLY_DESC)) {
                    return;
                }
            }
        }

        if (classNode.fields == null) {
            classNode.fields = new ArrayList<>();
        }

        if (classNode.methods == null) {
            classNode.methods = new ArrayList<>();
        }


        processClass(classNode);


        if (changed) {
            classNode.methods.forEach(methodNode -> {
                methodNode.maxStack = 1;
                methodNode.maxLocals = 1;
            });

            val classWriter = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
            ClassVisitor classVisitor = classWriter;
            if (IN_TEST) {
                classVisitor = wrapWithTestClassVisitors(classVisitor);
            }
            classNode.accept(classVisitor);
            val bytecode = classWriter.toByteArray();

            if (sourcePath != targetPath) {
                val targetDirPath = targetPath.getParent();
                if (targetDirPath != null) {
                    createDirectories(targetDirPath);
                }
            }

            write(targetPath, bytecode);
        }
    }


    private void processClass(ClassNode classNode) {
        if ((classNode.access & ACC_MODULE) != 0) {
            // skip `module-info`
            return;
        }

        if (classNode.methods == null || classNode.methods.isEmpty()) {
            // skip classes without methods
            return;
        }

        if (classNode.outerMethod != null) {
            // skip classes defined in methods
            return;
        }

        if (isKotlinClass(classNode) // skip Kotlin classes, as this language handles nullability itself
            || isGroovyGeneratedClosureClass(classNode) // skip closures generated by the Groovy compiler
        ) {
            return;
        }

        if (isAnnotatedBy(classNode, exclusionAnnotationDescs::contains)) {
            // skip classes annotated with exclusion annotation
            return;
        }

        if (!includeGeneratedCode) {
            if (isAnnotatedByGeneratedAnnotations(classNode)) {
                // skip generated classes
                return;
            }
        }

        if (isAnnotatedByValidationAnnotations(classNode)) {
            // skip classes annotated with Bean Validation annotations (or alternatives)
            return;
        }

        // TODO: check validation annotations on parent classes

        classNode.methods.forEach(methodNode ->
            processMethod(classNode, methodNode)
        );
    }


    @SuppressWarnings("java:S3776")
    private void processMethod(ClassNode classNode, MethodNode methodNode) {
        if ((methodNode.access & ACC_ABSTRACT) != 0 // skip abstract methods
            || (methodNode.access & ACC_SYNTHETIC) != 0 // skip synthetic methods
            || methodNode.instructions == null // skip methods without instructions
        ) {
            return;
        }

        if (!includePrivateMethods) {
            if ((methodNode.access & ACC_PRIVATE) != 0) {
                // skip private methods
                return;
            }
        }

        val paramTypes = getArgumentTypes(methodNode.desc);
        if (paramTypes.length == 0) {
            // skip methods without parameters
            return;
        }

        List<Integer> candidateNonNullParamIndexes = new ArrayList<>(paramTypes.length);
        for (int paramIndex = 0; paramIndex < paramTypes.length; ++paramIndex) {
            val paramType = paramTypes[paramIndex];
            if (isPrimitive(paramType)
                || !isExplicitParameter(methodNode, paramIndex)
                || isAnnotatedBy(methodNode, paramIndex, desc -> isDescEndsWith(desc, nullableAnnotationDescs))
            ) {
                continue;
            }
        }
        if (candidateNonNullParamIndexes.isEmpty()) {
            // skip methods if all parameters can't be non-null
            return;
        }

        if (methodNode.name.equals("equals")
            && methodNode.desc.equals("(Ljava/lang/Object;)Z")
        ) {
            // skip `equals` methods
            return;
        }

        if ((classNode.access & ACC_ENUM) != 0
            && methodNode.name.equals("valueOf")
            && methodNode.desc.equals("(Ljava/lang/String;)L" + classNode.name + ";")
        ) {
            // skip `valueOf` methods of enums
            return;
        }

        if (isAnnotatedBy(methodNode, exclusionAnnotationDescs::contains)) {
            // skip methods annotated with exclusion annotation
            return;
        }

        if (!includeGeneratedCode) {
            if (isAnnotatedByGeneratedAnnotations(methodNode)) {
                // skip generated methods
                return;
            }
        }

        if (isAnnotatedByValidationAnnotations(methodNode)) {
            // skip methods annotated with Bean Validation annotations (or alternatives)
            return;
        }

        // TODO: check validation annotations on methods from parent classes

        if (invokesSuperMethodOnly(methodNode)) {
            // skip methods that invoke super method only
            return;
        }

        throw new UnsupportedOperationException();
    }


    private static boolean isKotlinClass(ClassNode classNode) {
        if (isAnnotatedBy(classNode, "Lkotlin/Metadata;"::equals)) {
            // Kotlin classes are annotated with `kotlin.Metadata`
            return true;
        }

        if (isAnnotatedBy(classNode, desc -> desc.endsWith("/kotlin/Metadata;"))) {
            // handle relocated Kotlin classes
            return true;
        }

        return false;
    }

    private static boolean isGroovyGeneratedClosureClass(ClassNode classNode) {
        if (classNode.interfaces == null || classNode.interfaces.isEmpty()) {
            return false;
        }

        return classNode.interfaces.stream()
            .anyMatch("org/codehaus/groovy/runtime/GeneratedClosure"::equals);
    }


    private static String classNameToInternalName(String className) {
        return className.replace('.', '/');
    }

    private static String classNameToDescription(String className) {
        return 'L' + classNameToInternalName(className) + ';';
    }

    private static boolean isPrimitive(Type type) {
        return type.getDescriptor().length() == 1;
    }


    private boolean isAnnotatedByGeneratedAnnotations(ClassNode classNode) {
        return isAnnotatedBy(classNode, desc -> desc.endsWith(GENERATED_DESC_SUFFIX));
    }

    private boolean isAnnotatedByGeneratedAnnotations(MethodNode methodNode) {
        return isAnnotatedBy(methodNode, desc -> desc.endsWith(GENERATED_DESC_SUFFIX));
    }

    private boolean isAnnotatedByValidationAnnotations(ClassNode classNode) {
        return isAnnotatedBy(classNode, validationAnnotationDescs::contains)
            || isAnnotatedBy(classNode, desc -> validationAnnotationDescPrefixes.stream().anyMatch(desc::startsWith));
    }

    private boolean isAnnotatedByValidationAnnotations(MethodNode methodNode) {
        return isAnnotatedBy(methodNode, validationAnnotationDescs::contains)
            || isAnnotatedBy(methodNode, desc -> isDescStartsWith(desc, validationAnnotationDescPrefixes));
    }

    private boolean isDescStartsWith(String descriptor, Collection<String> prefixes) {
        return prefixes.stream().anyMatch(descriptor::startsWith);
    }

    private boolean isDescEndsWith(String descriptor, Collection<String> suffixes) {
        return suffixes.stream().anyMatch(descriptor::endsWith);
    }


    private static boolean isAnnotatedBy(
        ClassNode classNode,
        Predicate<String> descPredicate
    ) {
        return isAnnotatedBy(classNode.visibleAnnotations, descPredicate)
            || isAnnotatedBy(classNode.invisibleAnnotations, descPredicate);
    }

    private static boolean isAnnotatedBy(
        MethodNode methodNode,
        Predicate<String> descPredicate
    ) {
        return isAnnotatedBy(methodNode.visibleAnnotations, descPredicate)
            || isAnnotatedBy(methodNode.invisibleAnnotations, descPredicate);
    }

    private static boolean isAnnotatedBy(
        MethodNode methodNode,
        int paramIndex,
        Predicate<String> descPredicate
    ) {
        return isAnnotatedBy(getParameterVisibleAnnotations(methodNode, paramIndex), descPredicate)
            || isAnnotatedBy(getParameterInvisibleAnnotations(methodNode, paramIndex), descPredicate);
    }

    private static boolean isAnnotatedBy(
        @Nullable List<AnnotationNode> annotations,
        Predicate<String> descPredicate
    ) {
        if (annotations == null || annotations.isEmpty()) {
            return false;
        }

        return annotations.stream()
            .anyMatch(it -> descPredicate.test(it.desc));
    }


    @VisibleForTesting
    @SuppressWarnings("java:S3776")
    static String getParameterName(MethodNode methodNode, int paramIndex) {
        if (methodNode.parameters != null) {
            if (paramIndex < methodNode.parameters.size()) {
                val name = methodNode.parameters.get(paramIndex).name;
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        }

        if (methodNode.localVariables != null) {
            val localVarIndex = (methodNode.access & ACC_STATIC) != 0
                ? paramIndex
                : paramIndex + 1;
            if (localVarIndex < methodNode.localVariables.size()) {
                val name = methodNode.localVariables.get(localVarIndex).name;
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        }

        return "arg" + (paramIndex + 1);
    }

    @VisibleForTesting
    static boolean isExplicitParameter(MethodNode methodNode, int paramIndex) {
        if (methodNode.parameters != null) {
            if (paramIndex < methodNode.parameters.size()) {
                val access = methodNode.parameters.get(paramIndex).access;
                if ((access & ACC_SYNTHETIC) != 0
                    || (access & ACC_MANDATED) != 0
                ) {
                    return false;
                }
            }
        }

        return true;
    }

    @VisibleForTesting
    static List<AnnotationNode> getParameterVisibleAnnotations(MethodNode methodNode, int paramIndex) {
        if (methodNode.visibleParameterAnnotations != null) {
            if (paramIndex < methodNode.visibleParameterAnnotations.length) {
                return methodNode.visibleParameterAnnotations[paramIndex];
            }
        }

        return emptyList();
    }

    @VisibleForTesting
    static List<AnnotationNode> getParameterInvisibleAnnotations(MethodNode methodNode, int paramIndex) {
        if (methodNode.invisibleParameterAnnotations != null) {
            if (paramIndex < methodNode.invisibleParameterAnnotations.length) {
                return methodNode.invisibleParameterAnnotations[paramIndex];
            }
        }

        return emptyList();
    }


    @VisibleForTesting
    @SuppressWarnings("Indentation")
    static boolean invokesSuperMethodOnly(MethodNode methodNode) {
        if ((methodNode.access & ACC_STATIC) != 0) {
            // static methods can't be "super"
            return false;
        }

        AbstractInsnNode insn = getNextMeaningfulNode(methodNode.instructions, true);
        if (!(insn instanceof VarInsnNode
            && insn.getOpcode() == ALOAD
            && ((VarInsnNode) insn).var == 0
        )) {
            return false;
        }

        int paramVar = 1;
        for (val paramType : getArgumentTypes(methodNode.desc)) {
            insn = getNextMeaningfulInsn(insn, true);
            if (!(insn instanceof VarInsnNode
                && insn.getOpcode() == paramType.getOpcode(ILOAD)
                && ((VarInsnNode) insn).var == paramVar
            )) {
                return false;
            }
            paramVar += paramType.getSize();
        }

        insn = getNextMeaningfulInsn(insn, true);
        if (!(insn instanceof MethodInsnNode
            && insn.getOpcode() != INVOKESTATIC
            // we're calling a method of `this`, so we don't care about the owner
            && ((MethodInsnNode) insn).name.equals(methodNode.name)
            && ((MethodInsnNode) insn).desc.equals(methodNode.desc)
        )) {
            return false;
        }

        insn = getNextMeaningfulInsn(insn, true);
        if (!(insn instanceof InsnNode
            && insn.getOpcode() == getReturnType(methodNode.desc).getOpcode(IRETURN)
        )) {
            return false;
        }

        return true;
    }

    @Nullable
    private static AbstractInsnNode getNextMeaningfulNode(@Nullable InsnList insns, boolean skipLabels) {
        if (insns == null) {
            return null;
        }

        AbstractInsnNode insn = insns.getFirst();
        if (!isMeaningfulInsn(insn, skipLabels)) {
            insn = getNextMeaningfulInsn(insn, skipLabels);
        }
        return insn;
    }

    @Nullable
    private static AbstractInsnNode getNextMeaningfulInsn(@Nullable AbstractInsnNode insn, boolean skipLabels) {
        if (insn == null) {
            return null;
        }

        while (true) {
            insn = insn.getNext();

            if (insn != null && !isMeaningfulInsn(insn, skipLabels)) {
                continue;
            }

            return insn;
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isMeaningfulInsn(@Nullable AbstractInsnNode insn, boolean skipLabels) {
        if (insn == null
            || insn instanceof LineNumberNode
        ) {
            return false;
        }

        if (skipLabels && insn instanceof LabelNode) {
            return false;
        }

        return true;
    }

}
