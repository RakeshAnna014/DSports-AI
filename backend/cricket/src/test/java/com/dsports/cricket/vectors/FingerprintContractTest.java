package com.dsports.cricket.vectors;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.rules.MatchResultCalculator;
import com.dsports.cricket.domain.scoring.MatchScore;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fingerprint is a hash of canonical text, and canonical text is only the same in Java and in JavaScript for integers (7.0 is "7.0" in one and "7" in the
 * other). So a state must hold no fractions. Today it holds none: every number in a state is a count (runs, wickets, balls, overs, penalties, margins). The
 * run rate, the required rate and the economy are worked out for display from those counts and are not part of a state. These tests keep it that way: the
 * canonical writer refuses a fraction, the state's types cannot hold one, and no committed state contains one.
 */
class FingerprintContractTest {

    @Test
    void theCanonicalWriterRefusesAFraction() {
        var fraction = VectorFormat.tree("{\"runRate\": 7.0}");
        assertThatThrownBy(() -> VectorFormat.compact(fraction)).isInstanceOf(IllegalStateException.class).hasMessageContaining("non-integer");
        assertThatThrownBy(() -> VectorFormat.fingerprint(VectorFormat.tree("[1, 2, 6.5]"))).isInstanceOf(IllegalStateException.class);
        assertThat(VectorFormat.compact(VectorFormat.tree("{\"b\": [0, -3, 12], \"a\": null}"))).isEqualTo("{\"a\":null,\"b\":[0,-3,12]}");
    }

    @Test
    void theTypesOfAStateCannotHoldAFraction() {
        var problems = new ArrayList<String>();
        for (var root : List.of(MatchScore.class, InningsState.class, MatchResultCalculator.Result.class, MatchRules.class)) {
            check(root, new HashSet<>(), problems);
        }
        assertThat(problems).as("state components that are not integers, booleans, text, ids, enums, lists or records of those").isEmpty();
    }

    private static void check(Class<?> type, Set<Class<?>> seen, List<String> problems) {
        if (!type.isRecord() || !seen.add(type)) {
            return;
        }
        for (var component : type.getRecordComponents()) {
            checkType(type.getSimpleName() + "." + component.getName(), component.getGenericType(), seen, problems);
        }
    }

    private static void checkType(String where, Type type, Set<Class<?>> seen, List<String> problems) {
        if (type instanceof ParameterizedType p) {
            if (p.getRawType() == List.class) {
                checkType(where + "[]", p.getActualTypeArguments()[0], seen, problems);
            } else {
                problems.add(where + ": " + type);
            }
            return;
        }
        var cls = (Class<?>) type;
        boolean fine = cls == int.class || cls == Integer.class || cls == boolean.class || cls == Boolean.class || cls == String.class || cls == UUID.class || cls.isEnum();
        if (cls.isRecord()) {
            check(cls, seen, problems);
        } else if (!fine) {
            problems.add(where + ": " + cls.getName());
        }
    }

    @Test
    void noCommittedStateContainsAFraction() throws Exception {
        var fractions = new ArrayList<String>();
        int numbers = 0;
        var paths = new ArrayList<java.nio.file.Path>();
        for (var dir : new String[] {"golden", "random"}) {
            try (Stream<java.nio.file.Path> list = Files.list(VectorCorpus.folder().resolve(dir))) {
                list.forEach(paths::add);
            }
        }
        for (var path : paths) {
            for (JsonNode step : VectorFormat.tree(Files.readString(path)).get("steps")) {
                var state = step.get("expect").get("state");
                if (state != null) {
                    var stack = new ArrayList<JsonNode>(List.of(state));
                    while (!stack.isEmpty()) {
                        var node = stack.remove(stack.size() - 1);
                        if (node.isNumber()) {
                            numbers++;
                            if (!node.isIntegralNumber() || new BigDecimal(node.asText()).scale() > 0) {
                                fractions.add(path.getFileName() + ": " + node);
                            }
                        }
                        node.forEach(stack::add);
                    }
                }
            }
        }
        assertThat(numbers).as("numbers seen in recorded states").isGreaterThan(10_000);
        assertThat(fractions).isEmpty();
    }
}
