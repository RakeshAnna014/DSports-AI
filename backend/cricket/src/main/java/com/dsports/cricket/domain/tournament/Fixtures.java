package com.dsports.cricket.domain.tournament;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Who plays whom, worked out from the order of the teams alone: the same list always gives the same fixtures, so generating twice cannot disagree. Pure code.
 *
 * <p>A league is a round robin by the circle method (each pair meets exactly once; an odd number of teams gives each team one bye round, which is not a fixture). A knockout
 * is the first round of a seeded bracket: the bracket is the next power of two, seed 1 meets the lowest seed, and a seed with no opponent has a bye into round 2 (the
 * best seeds get the byes). Later knockout rounds are created when the earlier results are known, not here.
 */
public final class Fixtures {

    private Fixtures() {
    }

    /** {@code b} is null only for a knockout bye: {@code a} goes through without playing. */
    public record Pairing(int round, int position, UUID a, UUID b) {
        public boolean isBye() {
            return b == null;
        }
    }

    public static List<Pairing> roundRobin(List<UUID> teams) {
        requireDistinct(teams);
        var slots = new ArrayList<UUID>(teams);
        if (slots.size() % 2 == 1) {
            slots.add(null);
        }
        int n = slots.size();
        var out = new ArrayList<Pairing>();
        for (int round = 0; round < n - 1; round++) {
            int position = 0;
            for (int i = 0; i < n / 2; i++) {
                var a = slots.get(i);
                var b = slots.get(n - 1 - i);
                if (a != null && b != null) {
                    out.add(new Pairing(round + 1, position++, a, b));
                }
            }
            // Keep the first slot still and rotate the others by one.
            slots.add(1, slots.remove(n - 1));
        }
        return out;
    }

    /** The first round of a seeded knockout; {@code seeded} is best seed first. Fewer than two teams has no fixtures. */
    public static List<Pairing> knockoutFirstRound(List<UUID> seeded) {
        requireDistinct(seeded);
        if (seeded.size() < 2) {
            return List.of();
        }
        int size = Integer.highestOneBit(seeded.size() - 1) << 1;
        var out = new ArrayList<Pairing>();
        for (int i = 0; i < size / 2; i++) {
            int low = size - 1 - i;
            out.add(new Pairing(1, i, seeded.get(i), low < seeded.size() ? seeded.get(low) : null));
        }
        return out;
    }

    private static void requireDistinct(List<UUID> teams) {
        if (teams.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(teams).size() != teams.size()) {
            throw new IllegalArgumentException("A team can be in a tournament only once");
        }
    }
}
