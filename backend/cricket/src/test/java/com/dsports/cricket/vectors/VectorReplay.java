package com.dsports.cricket.vectors;

import com.dsports.cricket.domain.scoring.ScoringLog;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays one vector file against the Java engine: each command is read from its wire body (through the very class the server uses to read a request),
 * applied, and the answer (accepted or the rule that refused it, the seq, what an undo voided, the fingerprint and, where recorded, the whole state) must
 * equal the file. Used for the Java-made files and for the TypeScript-made ones, so both directions are held to the same standard.
 */
final class VectorReplay {
    private VectorReplay() {
    }

    /** Replays the file and returns how many steps it had. */
    static int replay(Path path) throws Exception {
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
        }
        return number;
    }
}
