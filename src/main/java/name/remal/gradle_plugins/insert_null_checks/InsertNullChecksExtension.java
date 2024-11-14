package name.remal.gradle_plugins.insert_null_checks;

import static java.util.stream.Collectors.toList;
import static name.remal.gradle_plugins.insert_null_checks.NullabilityAnnotations.EXCLUSION_ANNOTATION_CLASS_NAMES;
import static name.remal.gradle_plugins.insert_null_checks.NullabilityAnnotations.NOT_NULL_ANNOTATION_CLASS_NAMES;
import static name.remal.gradle_plugins.insert_null_checks.NullabilityAnnotations.NULLABLE_ANNOTATION_CLASS_NAMES;
import static name.remal.gradle_plugins.insert_null_checks.NullabilityAnnotations.VALIDATION_ANNOTATION_BASE_PACKAGES;
import static name.remal.gradle_plugins.insert_null_checks.NullabilityAnnotations.VALIDATION_ANNOTATION_CLASS_NAMES;
import static name.remal.gradle_plugins.toolkit.StringUtils.substringAfterLast;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class InsertNullChecksExtension implements InsertNullChecksProperties {

    {
        getIncludeTestCode().set(false);
        getIncludeGeneratedCode().set(true);
        getIncludePrivateMethods().set(false);
        getExclusionAnnotationClassNames().set(EXCLUSION_ANNOTATION_CLASS_NAMES);
        getValidationAnnotationClassNames().set(VALIDATION_ANNOTATION_CLASS_NAMES);
        getValidationAnnotationBasePackages().set(VALIDATION_ANNOTATION_BASE_PACKAGES);
        getNonNullAnnotationClassNames().set(NOT_NULL_ANNOTATION_CLASS_NAMES);
        getNullableAnnotationSimpleClassNames().set(NULLABLE_ANNOTATION_CLASS_NAMES.stream()
            .map(className -> substringAfterLast(className, "."))
            .distinct()
            .sorted()
            .collect(toList())
        );
    }

}
