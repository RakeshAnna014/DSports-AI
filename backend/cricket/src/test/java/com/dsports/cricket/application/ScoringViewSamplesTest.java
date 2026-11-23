package com.dsports.cricket.application;

import com.dsports.cricket.domain.scoring.GoldenMatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real scoring view, as the server builds it, after every command of the golden match (names "Lion 1", "Tiger 1", teams "Lions" and "Tigers"), written to
 * {@code testdata/cricket-engine/server/scoring-views-golden-match.json}. The phone builds the same view from its own log ({@code localView.ts}) and
 * {@code localView.parity.test.ts} compares the two after every step, so the pad, the scoreboard and the undo button show exactly what the server would have shown.
 * This test checks the committed file is current; {@code -Dvectors.write=true} rewrites it.
 */
class ScoringViewSamplesTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static Path file() {
        return Path.of(System.getProperty("basedir", ".")).resolve("../../testdata/cricket-engine/server/scoring-views-golden-match.json").normalize();
    }

    private static final String NL = "" + (char) 10;
    private static final String Q = "" + (char) 34;

    static String build() throws Exception {
        var fx = new ScoringFixture();
        var views = JSON.createArrayNode();
        for (var command : GoldenMatch.wholeMatch()) {
            var node = (ObjectNode) JSON.valueToTree(fx.score(command).view());
            // Everything that differs from run to run (the match id, the random client event ids inside the recent events) is fixed or left out: the file must be byte-stable.
            node.put("matchId", "00000000-0000-0000-0000-000000000099");
            node.putArray("recentEvents");
            views.add(node);
        }
        // One compact view per line: small, and a changed step is one changed line in a diff.
        var out = new StringBuilder("{" + NL + "  " + Q + "note" + Q + ": " + Q + "The server's ScoringView after each command of the golden match, as asked by the organiser. Written by ScoringViewSamplesTest; read by localView.parity.test.ts." + Q + "," + NL + "  " + Q + "views" + Q + ": [" + NL);
        for (int i = 0; i < views.size(); i++) {
            out.append("    ").append(JSON.writeValueAsString(views.get(i))).append(i < views.size() - 1 ? "," + NL : NL);
        }
        return out.append("  ]" + NL + "}" + NL).toString();
    }

    @Test
    void theCommittedViewsAreWhatTheServerBuildsNow() throws Exception {
        assertThat(Files.exists(file())).as("run with -Dvectors.write=true to create %s", file()).isTrue();
        assertThat(new String(Files.readAllBytes(file()), StandardCharsets.UTF_8)).as("regenerate with -Dvectors.write=true and review the diff").isEqualTo(build());
    }

    @Test
    @EnabledIfSystemProperty(named = "vectors.write", matches = "true")
    void writesTheViews() throws Exception {
        Files.createDirectories(file().getParent());
        Files.write(file(), build().getBytes(StandardCharsets.UTF_8));
    }
}
