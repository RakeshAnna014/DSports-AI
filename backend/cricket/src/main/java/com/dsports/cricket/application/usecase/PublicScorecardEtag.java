package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.result.PublicScorecard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The ETag of a public scorecard: the seq of the last event, a dash, and a SHA-256 digest of everything the response shows. The seq alone cannot work, because a
 * team or player can be renamed (or the match's details changed) without a ball being scored; the digest follows the response itself, so whatever a viewer would see
 * differently is a different tag. The seq in front only keeps the tag readable and makes two tags from different events differ at a glance.
 *
 * <p>The digest is taken over the record's {@code toString}, which lists every component in declaration order, so a field added to the response is covered with no
 * change here. It is stable across servers because every component is a string, number, boolean, instant, id or list of those.
 */
public final class PublicScorecardEtag {

    private static final int DIGEST_BYTES = 8;

    private PublicScorecardEtag() {
    }

    public static String of(PublicScorecard card) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(card.toString().getBytes(StandardCharsets.UTF_8));
            return "\"" + card.seq() + "-" + HexFormat.of().formatHex(digest, 0, DIGEST_BYTES) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
