package name.remal.gradle_plugins.insert_null_checks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import lombok.RequiredArgsConstructor;
import org.gradle.api.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@RequiredArgsConstructor
class InsertNullChecksPluginTest {

    final Project project;

    @BeforeEach
    void beforeEach() {
        project.getPluginManager().apply(InsertNullChecksPlugin.class);
    }

    @Test
    void test() {
        assertTrue(project.getPlugins().hasPlugin(InsertNullChecksPlugin.class));
    }

}
