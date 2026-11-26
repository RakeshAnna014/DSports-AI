package com.dsports.cricket.infrastructure.persistence;

import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.rules.MatchResultCalculator;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.scoring.MatchScore;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * JSON for the event log and the snapshot. The domain classes carry no Jackson annotations, so this codec maps them: records by their components, and the
 * commands by their kind (the {@code type} column) with the payload holding only the record. A change that makes an old row unreadable would be a new
 * {@code schema_version}, not an edit.
 */
public final class ScoringJsonCodec {

    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper = JsonMapper.builder()
        .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public String commandJson(ScoreCommand command) {
        return write(switch (command) {
            case ScoreCommand.Play p -> p.command();
            default -> command;
        });
    }

    public ScoreCommand command(ScoreCommand.Type type, String json) {
        return switch (type) {
            case START_INNINGS -> read(json, ScoreCommand.StartInnings.class);
            case END_MATCH -> read(json, ScoreCommand.EndMatch.class);
            case DELIVER -> new ScoreCommand.Play(read(json, ScoringCommand.Deliver.class));
            case SELECT_BATTER -> new ScoreCommand.Play(read(json, ScoringCommand.SelectBatter.class));
            case SELECT_BOWLER -> new ScoreCommand.Play(read(json, ScoringCommand.SelectBowler.class));
            case CHANGE_BOWLER -> new ScoreCommand.Play(read(json, ScoringCommand.ChangeBowlerMidOver.class));
            case RETIRE_BATTER -> new ScoreCommand.Play(read(json, ScoringCommand.RetireBatter.class));
            case REVISE_TARGET -> new ScoreCommand.Play(read(json, ScoringCommand.ReviseTarget.class));
            case END_INNINGS -> new ScoreCommand.Play(read(json, ScoringCommand.EndInnings.class));
        };
    }

    public String scoreJson(MatchScore score) {
        return write(score);
    }

    public MatchScore score(String json) {
        return read(json, MatchScore.class);
    }

    public String resultJson(MatchResultCalculator.Result result) {
        return write(result);
    }

    public String waiversJson(List<InningsState.Waiver> waivers) {
        return write(waivers);
    }

    public List<InningsState.Waiver> waivers(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<InningsState.Waiver>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored waiver list could not be read", e);
        }
    }

    public String voidsJson(List<Integer> voids) {
        return write(Map.of("voids", voids));
    }

    @SuppressWarnings("unchecked")
    public List<Integer> voids(String json) {
        return ((List<Number>) read(json, Map.class).get("voids")).stream().map(Number::intValue).toList();
    }

    public String reasonJson(String reason) {
        return write(Map.of("reason", reason));
    }

    public String reason(String json) {
        return String.valueOf(read(json, Map.class).get("reason"));
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write " + value.getClass().getSimpleName() + " as JSON", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored " + type.getSimpleName() + " could not be read", e);
        }
    }
}
