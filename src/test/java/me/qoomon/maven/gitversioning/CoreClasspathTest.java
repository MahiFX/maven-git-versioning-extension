package me.qoomon.maven.gitversioning;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CoreClasspathTest {

    // Maven 3.10 no longer ships maven-shared-utils in its core, so a core extension that references it
    // fails with NoClassDefFoundError before any project is read. The unit-test classpath still has it
    // (transitively via maven-core), which is why this checks the compiled classes instead.
    @Test
    void compiled_classes_do_not_reference_maven_shared_utils() throws IOException {
        Path classes = Paths.get("target", "classes");
        assertThat(classes).isDirectory();

        List<String> offenders;
        try (Stream<Path> files = Files.walk(classes)) {
            offenders = files
                    .filter(file -> file.toString().endsWith(".class"))
                    .filter(CoreClasspathTest::referencesMavenSharedUtils)
                    .map(Path::toString)
                    .collect(Collectors.toList());
        }

        assertThat(offenders).isEmpty();
    }

    private static boolean referencesMavenSharedUtils(Path classFile) {
        try {
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return bytes.contains("org/apache/maven/shared/utils");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
