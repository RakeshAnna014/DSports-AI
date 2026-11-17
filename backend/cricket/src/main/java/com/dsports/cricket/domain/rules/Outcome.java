package com.dsports.cricket.domain.rules;

/** What the engine says about one command: the new state, or the rule it breaks (the old state is then untouched). */
public sealed interface Outcome {

    /** {@code waivers} are the rules set aside by this very command (an event note: the bowler limit or the no-two-overs-in-a-row rule). */
    record Accepted(InningsState state, java.util.List<InningsState.Waiver> waivers) implements Outcome {
        public Accepted {
            waivers = java.util.List.copyOf(waivers);
        }

        public Accepted(InningsState state) {
            this(state, java.util.List.of());
        }
    }

    record Rejected(RuleViolation violation, String message) implements Outcome {
    }

    default boolean isAccepted() {
        return this instanceof Accepted;
    }
}
