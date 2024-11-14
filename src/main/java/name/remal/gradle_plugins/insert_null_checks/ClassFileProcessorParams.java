package name.remal.gradle_plugins.insert_null_checks;

import java.nio.file.Path;
import java.util.List;
import lombok.NonNull;
import lombok.Singular;
import lombok.experimental.SuperBuilder;

@SuperBuilder
abstract class ClassFileProcessorParams {

    @NonNull
    protected final Path sourcePath;

    @NonNull
    protected final Path targetPath;


    protected final boolean includeGeneratedCode;

    protected final boolean includePrivateMethods;

    @Singular
    protected final List<String> exclusionAnnotationClassNames;

    @Singular
    protected final List<String> validationAnnotationClassNames;

    @Singular
    protected final List<String> validationAnnotationBasePackages;

    @Singular
    protected final List<String> nonNullAnnotationClassNames;

    @Singular
    protected final List<String> nullableAnnotationSimpleClassNames;

}
