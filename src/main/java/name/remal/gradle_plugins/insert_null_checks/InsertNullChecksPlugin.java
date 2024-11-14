package name.remal.gradle_plugins.insert_null_checks;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static name.remal.gradle_plugins.toolkit.ObjectUtils.doNotInline;
import static name.remal.gradle_plugins.toolkit.SourceSetUtils.whenTestSourceSetRegistered;
import static name.remal.gradle_plugins.toolkit.reflection.MethodsInvoker.invokeMethod;

import java.util.ArrayList;
import java.util.List;
import lombok.SneakyThrows;
import lombok.val;
import name.remal.gradle_plugins.toolkit.reflection.ReflectionUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.AbstractCompile;

public abstract class InsertNullChecksPlugin implements Plugin<Project> {

    public static final String INSERT_NULL_CHECKS_EXTENSION_NAME = doNotInline("insertNullChecks");

    @Override
    @SneakyThrows
    public void apply(Project project) {
        val extension = project.getExtensions().create(
            INSERT_NULL_CHECKS_EXTENSION_NAME,
            InsertNullChecksExtension.class
        );


        val processingAction = project.getObjects().newInstance(ClassFileProcessorAction.class);

        List<SourceSet> testSourceSets = new ArrayList<>();
        whenTestSourceSetRegistered(project, testSourceSets::add);
        processingAction.getTestSourceSets()
            .value(project.provider(() -> testSourceSets))
            .finalizeValueOnRead();

        val propertyMethods = stream(InsertNullChecksProperties.class.getMethods())
            .filter(ReflectionUtils::isAbstract)
            .collect(toList());
        if (propertyMethods.isEmpty()) {
            throw new AssertionError("No property methods found in " + InsertNullChecksProperties.class);
        }
        for (val method : propertyMethods) {
            val sourceProperty = method.invoke(extension);
            val targetProperty = method.invoke(processingAction);
            invokeMethod(targetProperty, "value", Provider.class, (Provider<?>) sourceProperty);
            invokeMethod(targetProperty, "finalizeValueOnRead");
        }


        project.getTasks().withType(AbstractCompile.class).configureEach(task -> {
            task.doLast(processingAction);
        });
    }

}
