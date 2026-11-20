package com.dsports.cricket.vectors;

import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.Dismissal;
import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.rules.MatchResultCalculator;
import com.dsports.cricket.domain.rules.RuleViolation;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.MatchSetup;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoringLog;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds every file of {@code testdata/cricket-engine} in memory, deterministically: the same engine always gives the same bytes. The exporter writes them,
 * the "are current" test compares them with what is committed, so a change to the Java engine that alters any outcome fails until the files are regenerated and
 * the difference is reviewed.
 */
final class VectorCorpus {

    static final int FORMAT = 1;
    /** Random files keep a full state on every Nth accepted step and on the last one; every accepted step has its fingerprint. */
    private static final int STATE_EVERY = 25;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private VectorCorpus() {
    }

    /** The folder of the vectors, found from the module's directory ({@code backend/cricket}), the same in a checkout and in a clean worktree. */
    static Path folder() {
        return Path.of(System.getProperty("basedir", ".")).resolve("../../testdata/cricket-engine").normalize();
    }

    /** Every generated file, by its path inside the folder, manifest last. */
    static Map<String, String> build() {
        var files = new LinkedHashMap<String, String>();
        var stepCounts = new LinkedHashMap<String, Integer>();
        for (var scenario : VectorScenarios.all()) {
            var path = "golden/" + scenario.name() + ".json";
            files.put(path, file(scenario.name(), scenario.description(), scenario.setup(), scenario.steps(), 1, stepCounts, path));
        }
        for (long seed = 1; seed <= RandomMatches.SEEDS; seed++) {
            var match = RandomMatches.generate(seed);
            var path = String.format("random/seed-%04d.json", seed);
            files.put(path, file(String.format("random-%04d", seed), "A seeded random match: mostly sensible commands, many refused ones, some undos.", match.setup(), match.steps(),
                STATE_EVERY, stepCounts, path));
        }
        files.put("manifest.json", manifest(files, stepCounts));
        return files;
    }

    private static String file(String name, String description, MatchSetup setup, List<VectorStep> steps, int stateEvery, Map<String, Integer> stepCounts, String path) {
        var runner = new VectorRunner(setup);
        var lines = new ArrayList<String>();
        int accepted = 0;
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            var node = NODES.objectNode();
            VectorRunner.Result result;
            switch (step) {
                case VectorStep.Command c -> {
                    var body = VectorFormat.body(c.command());
                    if (!VectorFormat.command(body).equals(c.command())) {
                        throw new IllegalStateException(name + " step " + (i + 1) + ": the wire body does not read back as the same command: " + body);
                    }
                    node.put("op", "command");
                    node.set("command", body);
                    result = runner.command(c.command());
                }
                case VectorStep.Undo u -> {
                    node.put("op", "undo");
                    node.put("mode", u.mode().name());
                    result = runner.undo(u.mode());
                }
            }
            if (result.accepted()) {
                accepted++;
            }
            boolean last = i == steps.size() - 1;
            node.set("expect", runner.expect(result, result.accepted() && (stateEvery == 1 || accepted % stateEvery == 0 || last)));
            lines.add("    " + VectorFormat.compact(node));
        }
        stepCounts.put(path, steps.size());
        return "{\n  \"format\": " + FORMAT + ",\n  \"name\": \"" + name + "\",\n  \"description\": \"" + description + "\",\n  \"setup\": " + VectorFormat.compact(VectorFormat.setup(setup))
            + ",\n  \"steps\": [\n" + String.join(",\n", lines) + "\n  ]\n}\n";
    }

    private static <E extends Enum<E>> List<String> names(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

    /** The words both engines must know. The TypeScript build compares its own lists with these, so a new rule, command or dismissal cannot be missed there. */
    static Map<String, List<String>> vocabulary() {
        var v = new LinkedHashMap<String, List<String>>();
        v.put("commandTypes", names(ScoreCommand.Type.class));
        v.put("violations", names(RuleViolation.class));
        v.put("dismissalTypes", names(Dismissal.Type.class));
        v.put("creaseEnds", names(Dismissal.End.class));
        v.put("deliveryKinds", names(Delivery.Kind.class));
        v.put("runsTypes", names(Delivery.RunsType.class));
        v.put("endReasons", names(InningsState.EndReason.class));
        v.put("retirementTypes", names(ScoringCommand.RetirementType.class));
        v.put("endMatchReasons", names(ScoreCommand.EndMatch.Reason.class));
        v.put("waiverKinds", names(InningsState.WaiverKind.class));
        v.put("undoModes", names(ScoringLog.UndoMode.class));
        v.put("matchStatuses", names(MatchStatus.class));
        v.put("resultTypes", names(MatchResultCalculator.Type.class));
        v.put("nextActions", List.of("START_INNINGS", "SELECT_BATTER", "SELECT_BOWLER", "DELIVER", "NONE"));
        return v;
    }

    private static String list(List<String> items) {
        return items.stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(", ", "[", "]"));
    }

    private static String manifest(Map<String, String> files, Map<String, Integer> stepCounts) {
        var out = new StringBuilder("{\n  \"vectorFormat\": " + FORMAT + ",\n  \"engineVersion\": " + MatchScoring.ENGINE_VERSION + ",\n  \"files\": [\n");
        out.append(files.entrySet().stream().map(e -> "    {\"path\": \"" + e.getKey() + "\", \"sha256\": \"" + VectorFormat.sha256Hex(e.getValue()) + "\", \"steps\": " + stepCounts.get(e.getKey()) + "}")
            .collect(Collectors.joining(",\n")));
        out.append("\n  ],\n  \"vocabulary\": {\n");
        out.append(vocabulary().entrySet().stream().map(e -> "    \"" + e.getKey() + "\": " + list(e.getValue())).collect(Collectors.joining(",\n")));
        out.append("\n  },\n  \"notCoverable\": {\n");
        out.append(VectorScenarios.notCoverable().entrySet().stream().map(list -> "    \"" + list.getKey() + "\": {\n"
            + list.getValue().entrySet().stream().map(w -> "      \"" + w.getKey() + "\": \"" + w.getValue() + "\"").collect(Collectors.joining(",\n")) + "\n    }").collect(Collectors.joining(",\n")));
        out.append("\n  }\n}\n");
        return out.toString();
    }

    static void write(Map<String, String> files) throws java.io.IOException {
        var root = folder();
        for (var dir : List.of("golden", "random")) {
            var d = root.resolve(dir);
            if (Files.exists(d)) {
                try (var list = Files.list(d)) {
                    for (var f : (Iterable<Path>) list::iterator) {
                        Files.delete(f);
                    }
                }
            }
            Files.createDirectories(d);
        }
        for (var e : files.entrySet()) {
            Files.writeString(root.resolve(e.getKey()), e.getValue());
        }
    }
}
