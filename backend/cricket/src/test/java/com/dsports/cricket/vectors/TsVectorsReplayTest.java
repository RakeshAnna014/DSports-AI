package com.dsports.cricket.vectors;

import com.dsports.cricket.domain.scoring.MatchScoring;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other direction of the engine vectors. {@code testdata/cricket-engine/from-ts} is written by the TypeScript build ({@code pnpm run vectors:ts}): seeded random
 * matches played through the TypeScript engine, with its answer recorded at every step. Here the Java engine replays them. If the phone's engine would accept
 * a ball the server refuses (or the other way round, or the two disagree about the state, the fingerprint or what comes next) this fails, naming the file and
 * the step, long before a queued ball could be refused on a real match. The files are never edited to make Java pass.
 */
class TsVectorsReplayTest {

    private static Path folder() {
        return VectorCorpus.folder().resolve("from-ts");
    }

    private static List<Path> files() throws Exception {
        var files = new ArrayList<Path>();
        try (Stream<Path> list = Files.list(folder())) {
            list.filter(f -> f.getFileName().toString().startsWith("seed-") && f.toString().endsWith(".json")).sorted().forEach(files::add);
        }
        return files;
    }

    @Test
    void theManifestMatchesTheFilesAndTheEngineVersion() throws Exception {
        var manifest = VectorFormat.tree(Files.readString(folder().resolve("manifest.json")));
        assertThat(manifest.get("vectorFormat").asInt()).isEqualTo(VectorCorpus.FORMAT);
        assertThat(manifest.get("engineVersion").asInt()).as("the TypeScript engine's ENGINE_VERSION against the Java engine's").isEqualTo(MatchScoring.ENGINE_VERSION);
        var listed = new ArrayList<String>();
        for (JsonNode f : manifest.get("files")) {
            var name = f.get("path").asText();
            listed.add(name);
            var bytes = Files.readAllBytes(folder().resolve(name));
            assertThat(new String(bytes, StandardCharsets.UTF_8)).as("%s has no carriage returns (a checkout must not convert line endings)", name).doesNotContain("\r");
            assertThat(VectorFormat.sha256Hex(bytes)).as("hash of the bytes of %s", name).isEqualTo(f.get("sha256").asText());
        }
        assertThat(listed).containsExactlyInAnyOrderElementsOf(files().stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void everyStepTheTypeScriptEngineRecordedGivesTheSameAnswerInJava() throws Exception {
        int steps = 0;
        var paths = files();
        assertThat(paths).as("TypeScript-made vector files").hasSizeGreaterThanOrEqualTo(100);
        for (var path : paths) {
            steps += VectorReplay.replay(path);
        }
        assertThat(steps).as("steps replayed from the TypeScript side").isGreaterThan(3000);
    }
}
