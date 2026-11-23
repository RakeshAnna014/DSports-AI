package com.dsports.cricket.domain.scoring;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The fingerprint of a match's scoring state: the check that lets a phone know its own copy of the engine agrees with the server's. It is the first 8 bytes of
 * the SHA-256 of the canonical text of {@code {score, result, status}}, where canonical text is JSON with object keys sorted alphabetically at every level, no
 * spaces, and integers, booleans, null, and plain ASCII text only (ids as lower-case UUID text, enums by name). The TypeScript engine computes the very same
 * text ({@code frontend/src/features/cricket/engine/fingerprint.ts}); the shared vectors record this value for every accepted step, and a test replays them.
 *
 * <p>It is written by reflection over the domain records so that it cannot disagree with them: add a component to a state record and it is part of the
 * fingerprint at once (and the vectors fail until they are regenerated and the TypeScript side follows). A value of a type that cannot be written the same way
 * in JavaScript (a fraction, a non-ASCII string) is refused, not guessed.
 */
public final class StateFingerprint {

    private record Component(String name, Method accessor) {
    }

    /** The components of each record class, found once (reflection is the slow part, and the same few classes are written thousands of times). */
    private static final ClassValue<List<Component>> COMPONENTS = new ClassValue<>() {
        @Override
        protected List<Component> computeValue(Class<?> type) {
            var list = new ArrayList<Component>();
            for (var component : type.getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                list.add(new Component(component.getName(), accessor));
            }
            return List.copyOf(list);
        }
    };

    private StateFingerprint() {
    }

    public static String of(MatchScore score) {
        return HexFormat.of().formatHex(sha256(canonicalText(score).getBytes(StandardCharsets.UTF_8)), 0, 8);
    }

    /** The text that is hashed. Exposed so a test can show exactly what was fingerprinted. */
    public static String canonicalText(MatchScore score) {
        var root = new TreeMap<String, Object>();
        root.put("score", score);
        root.put("result", score.result().orElse(null));
        root.put("status", score.status());
        var out = new StringBuilder(4096);
        write(out, root);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case Boolean b -> out.append(b ? "true" : "false");
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Short s -> out.append(s);
            case String s -> text(out, s);
            case UUID id -> text(out, id.toString());
            case Enum<?> e -> text(out, e.name());
            case Collection<?> items -> {
                out.append('[');
                boolean first = true;
                for (var item : items) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(out, item);
                }
                out.append(']');
            }
            case Map<?, ?> map -> {
                var sorted = new TreeMap<String, Object>();
                map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
                object(out, sorted);
            }
            default -> {
                if (!value.getClass().isRecord()) {
                    throw new IllegalStateException("A " + value.getClass().getName() + " cannot be fingerprinted (only integers, booleans, text, ids, enums, lists and records)");
                }
                var fields = new TreeMap<String, Object>();
                for (var component : COMPONENTS.get(value.getClass())) {
                    try {
                        fields.put(component.name(), component.accessor().invoke(value));
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        throw new IllegalStateException("Could not read " + component.name() + " of " + value.getClass().getSimpleName(), e);
                    }
                }
                object(out, fields);
            }
        }
    }

    private static void object(StringBuilder out, TreeMap<String, Object> fields) {
        out.append('{');
        boolean first = true;
        for (var entry : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            text(out, entry.getKey());
            out.append(':');
            write(out, entry.getValue());
        }
        out.append('}');
    }

    private static void text(StringBuilder out, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e || c == '"' || c == '\\') {
                throw new IllegalStateException("Only plain ASCII text can be fingerprinted, got " + s);
            }
        }
        out.append('"').append(s).append('"');
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
