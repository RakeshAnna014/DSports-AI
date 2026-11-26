package com.dsports.cricket.vectors;

import com.dsports.cricket.domain.scoring.MatchScore;
import com.dsports.cricket.domain.scoring.ScoringLog;
import com.dsports.cricket.domain.scoring.StateFingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint the server sends with every scoring view ({@link StateFingerprint}, written in main code by reflection over the domain records) must be exactly the
 * one the shared vectors record, which the TypeScript engine reproduces: for every accepted step of every vector file, golden and Java- and TypeScript-made,
 * the fingerprint of the score the engine is in equals the recorded one. If these ever differ, a phone would switch offline scoring off against a healthy server
 * (or, worse, trust a state it should not).
 */
class StateFingerprintTest {

    private static List<Path> files() throws Exception {
        var all = new ArrayList<Path>();
        for (var dir : new String[] {"golden", "random", "from-ts"}) {
            try (Stream<Path> list = Files.list(VectorCorpus.folder().resolve(dir))) {
                list.filter(f -> f.getFileName().toString().endsWith(".json") && !f.getFileName().toString().equals("manifest.json")).sorted().forEach(all::add);
            }
        }
        return all;
    }

    @Test
    void theServersFingerprintEqualsTheRecordedOneAtEveryAcceptedStepOfEveryVectorFile() throws Exception {
        int checked = 0;
        for (var path : files()) {
            var file = VectorFormat.tree(Files.readString(path));
            var runner = new VectorRunner(VectorFormat.setup(file.get("setup")));
            int number = 0;
            for (JsonNode step : file.get("steps")) {
                number++;
                if (step.get("op").asText().equals("command")) {
                    runner.command(VectorFormat.command(step.get("command")));
                } else {
                    runner.undo(ScoringLog.UndoMode.valueOf(step.get("mode").asText()));
                }
                var recorded = step.get("expect");
                if (recorded.has("fp")) {
                    MatchScore score = runner.score();
                    assertThat(StateFingerprint.of(score)).as("%s step %d", path.getFileName(), number).isEqualTo(recorded.get("fp").asText());
                    checked++;
                }
            }
        }
        assertThat(checked).as("fingerprints compared").isGreaterThan(5000);
    }

    @Test
    void theTextThatIsHashedIsTheCanonicalTextOfTheVectorsState() throws Exception {
        var file = VectorFormat.tree(Files.readString(VectorCorpus.folder().resolve("golden/golden-match.json")));
        var runner = new VectorRunner(VectorFormat.setup(file.get("setup")));
        for (JsonNode step : file.get("steps")) {
            runner.command(VectorFormat.command(step.get("command")));
            assertThat(StateFingerprint.canonicalText(runner.score())).isEqualTo(VectorFormat.compact(runner.state()));
        }
    }

    @Test
    void anEmptyMatchHasAFingerprintToo() {
        assertThat(StateFingerprint.of(MatchScore.empty())).hasSize(16);
    }
}
