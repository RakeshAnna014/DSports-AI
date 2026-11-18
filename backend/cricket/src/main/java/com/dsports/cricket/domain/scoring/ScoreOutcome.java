package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.rules.RuleViolation;

import java.util.List;

/** The result of one match-level command: the new score and any rule set aside as a last resort, or the rule that was broken. */
public sealed interface ScoreOutcome {

    record Accepted(MatchScore score, List<InningsState.Waiver> waivers) implements ScoreOutcome {
        public Accepted {
            waivers = List.copyOf(waivers);
        }
    }

    record Rejected(RuleViolation violation, String message) implements ScoreOutcome {
    }
}
