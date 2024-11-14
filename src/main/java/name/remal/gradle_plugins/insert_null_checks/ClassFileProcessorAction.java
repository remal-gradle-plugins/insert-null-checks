package name.remal.gradle_plugins.insert_null_checks;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static name.remal.gradle_plugins.toolkit.AbstractCompileUtils.getDestinationDir;
import static name.remal.gradle_plugins.toolkit.SourceSetUtils.isCompiledBy;

import javax.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.AbstractCompile;

@RequiredArgsConstructor
abstract class ClassFileProcessorAction implements Action<Task>, InsertNullChecksProperties {

    public abstract ListProperty<SourceSet> getTestSourceSets();

    @Override
    public void execute(Task untypedTask) {
        val task = (AbstractCompile) untypedTask;
        val destinationDir = getDestinationDir(task);
        if (destinationDir == null) {
            return;
        }

        if (FALSE.equals(getIncludeTestCode().get())) {
            val isCompilingTestSources = getTestSourceSets().get().stream()
                .anyMatch(sourceSet -> isCompiledBy(sourceSet, task));
            if (isCompilingTestSources) {
                return;
            }
        }

        val fileTree = getObjects().fileTree().from(destinationDir);
        fileTree.include("**/*.class");
        fileTree.visit(details -> {
            if (!details.isDirectory()) {
                val path = details.getFile().toPath();
                ClassFileProcessor.builder()
                    .sourcePath(path)
                    .targetPath(path)
                    .includeGeneratedCode(TRUE.equals(getIncludeGeneratedCode().get()))
                    .includePrivateMethods(TRUE.equals(getIncludePrivateMethods().get()))
                    .exclusionAnnotationClassNames(getNonNullAnnotationClassNames().get())
                    .validationAnnotationClassNames(getValidationAnnotationClassNames().get())
                    .validationAnnotationBasePackages(getValidationAnnotationBasePackages().get())
                    .nonNullAnnotationClassNames(getNonNullAnnotationClassNames().get())
                    .nullableAnnotationSimpleClassNames(getNullableAnnotationSimpleClassNames().get())
                    .build()
                    .process();
            }
        });
    }


    @Inject
    protected abstract ObjectFactory getObjects();

}
