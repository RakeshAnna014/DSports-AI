package com.dsports.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every module ships its own Flyway scripts into the shared {@code classpath:db/migration} location.
 * The bootstrap test classpath contains all modules, so it sees exactly what Flyway sees at startup.
 */
class FlywayMigrationVersionTest {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__.+\\.sql$");

    @Test
    void migrationVersionsAreUniqueAcrossAllModules() throws IOException {
        var byVersion = versionedScripts().stream()
            .collect(Collectors.groupingBy(Script::version, Collectors.mapping(Script::location, Collectors.toList())));

        var duplicates = byVersion.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(duplicates)
            .as("Flyway refuses to start when two scripts share a version")
            .isEmpty();
    }

    @Test
    void allModuleMigrationsAreOnTheClasspath() throws IOException {
        var names = versionedScripts().stream().map(Script::location).toList();

        assertThat(names).anyMatch(n -> n.contains("create_notification_tables"));
        assertThat(names).anyMatch(n -> n.contains("create_shipment_tables"));
        assertThat(names).anyMatch(n -> n.contains("create_review_tables"));
        assertThat(names).anyMatch(n -> n.contains("create_wishlist_tables"));
        assertThat(names).anyMatch(n -> n.contains("allow_completed_cart_status"));
    }

    /** Data-destroying statements must never reach production through a migration. */
    private static final Pattern DESTRUCTIVE = Pattern.compile(
        "\\b(DROP\\s+TABLE|DROP\\s+SCHEMA|DROP\\s+COLUMN|TRUNCATE|DELETE\\s+FROM)\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void migrationsContainNoDestructiveStatements() throws IOException {
        var offenders = new ArrayList<String>();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql")) {
            var sql = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("--[^\\n]*", "");
            if (DESTRUCTIVE.matcher(sql).find()) {
                offenders.add(resource.getFilename());
            }
        }
        assertThat(offenders).as("migrations with destructive statements").isEmpty();
    }

    @Test
    void migrationFileNamesFollowFlywayConvention() throws IOException {
        var invalid = new ArrayList<String>();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql")) {
            var name = resource.getFilename();
            if (name != null && !VERSIONED.matcher(name).matches()) {
                invalid.add(name);
            }
        }
        assertThat(invalid).as("migration files Flyway would silently ignore").isEmpty();
    }

    private static List<Script> versionedScripts() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql");
        var scripts = new ArrayList<Script>();
        for (var resource : resources) {
            var filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            var matcher = VERSIONED.matcher(filename);
            if (matcher.matches()) {
                scripts.add(new Script(matcher.group(1).replace('_', '.'), resource.getURL().toString()));
            }
        }
        assertThat(scripts).as("migration scripts found on classpath").isNotEmpty();
        return scripts;
    }

    private record Script(String version, String location) {}
}
