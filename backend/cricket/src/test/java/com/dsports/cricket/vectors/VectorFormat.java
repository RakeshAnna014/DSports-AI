package com.dsports.cricket.vectors;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.scoring.MatchSetup;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.infrastructure.persistence.ScoringJsonCodec;
import com.dsports.cricket.interfaces.ScoringRequests;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The shared file format of the engine vectors ({@code testdata/cricket-engine}): how commands are written (exactly the body the phone sends to the scoring
 * route), how a setup is written, and the canonical form of a state and its fingerprint. Everything a TypeScript port has to reproduce byte for byte lives here.
 *
 * <p>Canonical form: JSON with object keys sorted alphabetically at every level, no spaces, numbers as plain integers, ids as lower-case UUID text.
 * Fingerprint: the first 8 bytes of the SHA-256 of the canonical text of the state, as 16 hex characters.
 */
final class VectorFormat {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final ScoringJsonCodec CODEC = new ScoringJsonCodec();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private VectorFormat() {
    }

    // ---- canonical text and fingerprint ----------------------------------------------------------------------------

    static JsonNode canonical(JsonNode node) {
        if (node.isNumber() && !node.isIntegralNumber()) {
            // The fingerprint is defined over integers only (no run rates, no economy): a fraction would be written differently by Java and by JavaScript.
            throw new IllegalStateException("A non-integer number cannot be part of a fingerprinted state: " + node);
        }
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), canonical(e.getValue())));
            var out = NODES.objectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            var out = NODES.arrayNode();
            node.forEach(item -> out.add(canonical(item)));
            return out;
        }
        return node;
    }

    static String compact(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(canonical(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String fingerprint(JsonNode state) {
        return HexFormat.of().formatHex(sha256(compact(state).getBytes(StandardCharsets.UTF_8)), 0, 8);
    }

    /** SHA-256 of the exact bytes of a file. Nothing is normalised: the files are checked out byte for byte (see {@code .gitattributes}). */
    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }

    static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- commands: the body the phone sends ------------------------------------------------------------------------

    /** The wire body of a command (the {@code command} field of the scoring request). Fields that are null are left out. */
    static ObjectNode body(ScoreCommand command) {
        var o = NODES.objectNode();
        o.put("type", command.type().name());
        switch (command) {
            case ScoreCommand.StartInnings c -> {
                o.put("striker", c.striker().toString());
                o.put("nonStriker", c.nonStriker().toString());
                o.put("bowler", c.bowler().toString());
            }
            case ScoreCommand.EndMatch c -> o.put("reason", c.reason().name());
            case ScoreCommand.Play p -> {
                switch (p.command()) {
                    case ScoringCommand.Deliver d -> o.set("delivery", delivery(d.delivery()));
                    case ScoringCommand.SelectBatter c -> o.put("batterId", c.batterId().toString());
                    case ScoringCommand.SelectBowler c -> o.put("bowlerId", c.bowlerId().toString());
                    case ScoringCommand.ChangeBowlerMidOver c -> o.put("bowlerId", c.newBowlerId().toString());
                    case ScoringCommand.RetireBatter c -> {
                        o.put("batterId", c.batterId().toString());
                        o.put("retirement", c.type().name());
                    }
                    case ScoringCommand.ReviseTarget c -> o.put("target", c.target());
                    case ScoringCommand.EndInnings c -> o.put("reason", c.reason().name());
                    case ScoringCommand.StartInnings c -> throw new IllegalStateException("Play never carries StartInnings");
                }
            }
        }
        return o;
    }

    private static ObjectNode delivery(Delivery d) {
        var o = NODES.objectNode();
        o.put("kind", d.kind().name());
        o.put("runs", d.runs());
        if (d.runsType() != null) {
            o.put("runsType", d.runsType().name());
        }
        o.put("boundary", d.boundary());
        if (d.dismissal() != null) {
            var x = d.dismissal();
            var dn = o.putObject("dismissal");
            dn.put("type", x.type().name());
            if (x.batterOut() != null) {
                dn.put("batterOut", x.batterOut().toString());
            }
            if (x.end() != null) {
                dn.put("end", x.end().name());
            }
            if (x.fielderId() != null) {
                dn.put("fielderId", x.fielderId().toString());
            }
        }
        return o;
    }

    /** Reads a wire body back through the very class the server uses to read the request, so a vector can only hold what the phone could send. */
    static ScoreCommand command(JsonNode body) {
        try {
            return MAPPER.treeToValue(body, ScoringRequests.CommandBody.class).toCommand();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Not a command body: " + body, e);
        }
    }

    // ---- setup -----------------------------------------------------------------------------------------------------

    static ObjectNode setup(MatchSetup s) {
        var o = NODES.objectNode();
        o.put("overs", s.overs());
        o.set("rules", MAPPER.valueToTree(s.rules()));
        o.put("teamA", s.teamAId().toString());
        o.put("teamB", s.teamBId().toString());
        o.put("battingFirst", s.battingFirstTeamId().toString());
        o.set("xiA", ids(s.xiA()));
        o.set("xiB", ids(s.xiB()));
        return o;
    }

    static MatchSetup setup(JsonNode n) {
        try {
            return new MatchSetup(n.get("overs").asInt(), MAPPER.treeToValue(n.get("rules"), MatchRules.class), UUID.fromString(n.get("teamA").asText()),
                UUID.fromString(n.get("teamB").asText()), UUID.fromString(n.get("battingFirst").asText()), uuids(n.get("xiA")), uuids(n.get("xiB")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ArrayNode ids(List<UUID> ids) {
        var a = NODES.arrayNode();
        ids.forEach(id -> a.add(id.toString()));
        return a;
    }

    private static List<UUID> uuids(JsonNode array) {
        var out = new ArrayList<UUID>();
        array.forEach(n -> out.add(UUID.fromString(n.asText())));
        return out;
    }
}
