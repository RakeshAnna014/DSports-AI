package com.dsports.cricket.application.result;

import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.scoring.ScoreCommand;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A scoring command in the exact shape the phone sends it to the scoring route (the {@code command} field of the request) and the TypeScript engine reads it
 * ({@code wire.ts}). Fields that are null are left out. Used for the engine-state response and for the shared engine vectors, so there is one writer.
 */
public final class CommandWire {

    private CommandWire() {
    }

    public static Map<String, Object> body(ScoreCommand command) {
        var o = new LinkedHashMap<String, Object>();
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
                    case ScoringCommand.Deliver d -> o.put("delivery", delivery(d.delivery()));
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

    private static Map<String, Object> delivery(Delivery d) {
        var o = new LinkedHashMap<String, Object>();
        o.put("kind", d.kind().name());
        o.put("runs", d.runs());
        if (d.runsType() != null) {
            o.put("runsType", d.runsType().name());
        }
        o.put("boundary", d.boundary());
        if (d.dismissal() != null) {
            var x = d.dismissal();
            var dn = new LinkedHashMap<String, Object>();
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
            o.put("dismissal", dn);
        }
        return o;
    }
}
