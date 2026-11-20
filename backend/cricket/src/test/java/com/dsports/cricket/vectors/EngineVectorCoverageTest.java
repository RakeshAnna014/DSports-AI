package com.dsports.cricket.vectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The files exercise every word of the engine's vocabulary: every command type, every rule that can refuse a command, every kind of dismissal, ball, retirement,
 * end of innings, waiver, undo mode, match status and result. A new rule, command or dismissal without a vector fails here, so it cannot ship untested on the
 * other side. The only exceptions are listed in the manifest with a reason ({@code notCoverable}).
 */
class EngineVectorCoverageTest {

    @Test
    void everyWordOfTheVocabularyAppearsInTheFiles() throws Exception {
        var seen = new LinkedHashMap<String, Set<String>>();
        VectorCorpus.vocabulary().keySet().forEach(k -> seen.put(k, new LinkedHashSet<>()));
        var root = VectorCorpus.folder();
        var paths = new ArrayList<java.nio.file.Path>();
        for (var dir : new String[] {"golden", "random"}) {
            try (Stream<java.nio.file.Path> list = Files.list(root.resolve(dir))) {
                list.forEach(paths::add);
            }
        }
        for (var path : paths) {
            for (JsonNode step : VectorFormat.tree(Files.readString(path)).get("steps")) {
                var expect = step.get("expect");
                var result = expect.get("result").asText();
                if (result.equals("rejected")) {
                    seen.get("violations").add(expect.get("violation").asText());
                    continue;
                }
                if (!result.equals("accepted")) {
                    continue;
                }
                if (step.get("op").asText().equals("undo")) {
                    seen.get("undoModes").add(step.get("mode").asText());
                } else {
                    record(seen, step.get("command"));
                }
                seen.get("matchStatuses").add(expect.get("status").asText());
                seen.get("nextActions").add(expect.get("next").asText());
                if (!expect.get("end").isNull()) {
                    seen.get("endReasons").add(expect.get("end").asText());
                }
                if (!expect.get("resultType").isNull()) {
                    seen.get("resultTypes").add(expect.get("resultType").asText());
                }
                if (expect.has("waivers")) {
                    expect.get("waivers").forEach(w -> seen.get("waiverKinds").add(w.get("kind").asText()));
                }
            }
        }
        var manifest = VectorFormat.tree(Files.readString(root.resolve("manifest.json")));
        var notCoverable = new LinkedHashSet<String>();
        manifest.get("notCoverable").fields().forEachRemaining(list -> list.getValue().fieldNames().forEachRemaining(word -> notCoverable.add(list.getKey() + ": " + word)));

        var missing = new ArrayList<String>();
        for (var entry : VectorCorpus.vocabulary().entrySet()) {
            for (var word : entry.getValue()) {
                if (!seen.get(entry.getKey()).contains(word) && !notCoverable.contains(entry.getKey() + ": " + word)) {
                    missing.add(entry.getKey() + ": " + word);
                }
            }
        }
        assertThat(missing).as("words of the engine's vocabulary with no vector (add a scenario in VectorScenarios, or list it as notCoverable with a reason)").isEmpty();
        for (var entry : notCoverable) {
            var parts = entry.split(": ");
            assertThat(seen.get(parts[0])).as("%s is listed as not coverable but a vector covers it: take it off the list", entry).doesNotContain(parts[1]);
        }
        assertThat(sawNothingToUndo(paths)).as("an undo with nothing to undo").isTrue();
    }

    private static boolean sawNothingToUndo(List<java.nio.file.Path> paths) throws Exception {
        for (var path : paths) {
            for (JsonNode step : VectorFormat.tree(Files.readString(path)).get("steps")) {
                if (step.get("expect").get("result").asText().equals("nothing_to_undo")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void record(Map<String, Set<String>> seen, JsonNode command) {
        var type = command.get("type").asText();
        seen.get("commandTypes").add(type);
        switch (type) {
            case "DELIVER" -> {
                var delivery = command.get("delivery");
                seen.get("deliveryKinds").add(delivery.get("kind").asText());
                if (delivery.has("runsType")) {
                    seen.get("runsTypes").add(delivery.get("runsType").asText());
                }
                if (delivery.has("dismissal")) {
                    var d = delivery.get("dismissal");
                    seen.get("dismissalTypes").add(d.get("type").asText());
                    if (d.has("end")) {
                        seen.get("creaseEnds").add(d.get("end").asText());
                    }
                }
            }
            case "RETIRE_BATTER" -> seen.get("retirementTypes").add(command.get("retirement").asText());
            case "END_MATCH" -> seen.get("endMatchReasons").add(command.get("reason").asText());
            default -> {
            }
        }
    }
}
