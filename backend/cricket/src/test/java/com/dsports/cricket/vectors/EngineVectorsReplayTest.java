package com.dsports.cricket.vectors;

import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.ScoringLog;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the committed files the way the TypeScript engine will, and replays them against the Java engine: each command is read from its wire body, applied,
 * and the answer (accepted or the rule that refused it, the seq, what an undo voided, the fingerprint and, where recorded, the whole state) must equal the file.
 * The manifest's hashes and its engine version are checked here too, so a hand-edited file or a rules change without a version bump is caught on this side as well.
 */
class EngineVectorsReplayTest {

    private static List<Path> vectorFiles() throws Exception {
        var root = VectorCorpus.folder();
        var files = new ArrayList<Path>();
        for (var dir : new String[] {"golden", "random"}) {
            try (Stream<Path> list = Files.list(root.resolve(dir))) {
                list.filter(f -> f.toString().endsWith(".json")).sorted().forEach(files::add);
            }
        }
        return files;
    }

    @Test
    void theManifestMatchesTheFilesAndTheEngineVersion() throws Exception {
        var root = VectorCorpus.folder();
        var manifest = VectorFormat.tree(Files.readString(root.resolve("manifest.json")));
        assertThat(manifest.get("vectorFormat").asInt()).isEqualTo(VectorCorpus.FORMAT);
        assertThat(manifest.get("engineVersion").asInt()).as("the Java engine's ENGINE_VERSION").isEqualTo(MatchScoring.ENGINE_VERSION);
        var listed = new ArrayList<String>();
        for (JsonNode f : manifest.get("files")) {
            listed.add(f.get("path").asText());
            var bytes = Files.readAllBytes(root.resolve(f.get("path").asText()));
            assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).as("%s has no carriage returns (a checkout must not convert line endings)", f.get("path").asText()).doesNotContain("\r");
            assertThat(VectorFormat.sha256Hex(bytes)).as("hash of the bytes of %s", f.get("path").asText()).isEqualTo(f.get("sha256").asText());
        }
        assertThat(listed).hasSameSizeAs(vectorFiles()).containsExactlyInAnyOrderElementsOf(vectorFiles().stream().map(p -> root.relativize(p).toString().replace('\\', '/')).toList());
    }

    @Test
    void everyStepOfEveryFileGivesTheRecordedAnswer() throws Exception {
        int steps = 0;
        for (var path : vectorFiles()) {
            var file = VectorFormat.tree(Files.readString(path));
            var runner = new VectorRunner(VectorFormat.setup(file.get("setup")));
            int number = 0;
            for (JsonNode step : file.get("steps")) {
                number++;
                var where = path.getFileName() + " step " + number;
                VectorRunner.Result result;
                if (step.get("op").asText().equals("command")) {
                    var command = VectorFormat.command(step.get("command"));
                    assertThat(VectorFormat.canonical(VectorFormat.body(command))).as("%s: the body is the canonical one", where).isEqualTo(VectorFormat.canonical(step.get("command")));
                    result = runner.command(command);
                } else {
                    result = runner.undo(ScoringLog.UndoMode.valueOf(step.get("mode").asText()));
                }
                var recorded = step.get("expect");
                var actual = runner.expect(result, recorded.has("state"));
                assertThat(VectorFormat.compact(actual)).as(where).isEqualTo(VectorFormat.compact(recorded));
                steps++;
            }
        }
        assertThat(steps).as("steps replayed").isGreaterThan(3000);
    }
}
