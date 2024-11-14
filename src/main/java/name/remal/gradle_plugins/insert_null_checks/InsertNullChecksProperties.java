package name.remal.gradle_plugins.insert_null_checks;

import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

interface InsertNullChecksProperties {

    default void test() {
    }

    Property<Boolean> getIncludeTestCode();

    Property<Boolean> getIncludeGeneratedCode();

    Property<Boolean> getIncludePrivateMethods();

    SetProperty<String> getExclusionAnnotationClassNames();

    SetProperty<String> getValidationAnnotationClassNames();

    SetProperty<String> getValidationAnnotationBasePackages();

    SetProperty<String> getNonNullAnnotationClassNames();

    SetProperty<String> getNullableAnnotationSimpleClassNames();

}
