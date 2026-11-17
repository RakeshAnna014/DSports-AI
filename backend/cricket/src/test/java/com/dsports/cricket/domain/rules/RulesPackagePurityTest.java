package com.dsports.cricket.domain.rules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The scoring rules are plain Java: nothing from Spring, R2DBC, Jackson, reactive streams, the clock or random numbers may be imported into the package. */
class RulesPackagePurityTest {

    private static final Path RULES = Path.of("src/main/java/com/dsports/cricket/domain/rules");
    private static final List<String> FORBIDDEN_USES = List.of("java.time.Instant.now", "LocalDateTime.now", "System.currentTimeMillis", "System.nanoTime", "new Random", "Math.random",
        "UUID.randomUUID", "ThreadLocalRandom", "System.out", "System.err");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.list(RULES)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void the_rules_package_exists_and_has_its_sources() throws IOException {
        assertThat(sources()).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void it_imports_only_java_and_the_cricket_domain() throws IOException {
        for (var file : sources()) {
            for (var line : Files.readAllLines(file)) {
                var trimmed = line.trim();
                if (trimmed.startsWith("import ")) {
                    var imported = trimmed.replaceFirst("^import (static )?", "");
                    assertThat(imported.startsWith("java.") || imported.startsWith("com.dsports.cricket.domain."))
                        .as("%s imports %s", file.getFileName(), imported).isTrue();
                }
            }
        }
    }

    @Test
    void it_does_not_read_the_clock_or_use_randomness() throws IOException {
        for (var file : sources()) {
            var text = Files.readString(file);
            for (var use : FORBIDDEN_USES) {
                assertThat(text).as("%s uses %s", file.getFileName(), use).doesNotContain(use);
            }
        }
    }
}
