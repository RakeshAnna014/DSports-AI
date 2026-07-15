package com.dsports.identity.domain.model;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.domain.kernel.ValueObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

public final class ProfileImageUrl implements ValueObject {

    private static final int MAX_LENGTH = 2048;

    private final String value;

    private ProfileImageUrl(String value) {
        this.value = value;
    }

    public static ProfileImageUrl from(String url) {
        if (url == null || url.isBlank()) {
            throw new IdentityDomainException(ErrorCode.INVALID_PROFILE_IMAGE_URL,
                    "Profile image URL must not be null or empty");
        }
        String trimmed = url.strip();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IdentityDomainException(ErrorCode.INVALID_PROFILE_IMAGE_URL,
                    "Profile image URL must not exceed " + MAX_LENGTH + " characters");
        }
        if (trimmed.startsWith("javascript:") || trimmed.startsWith("data:")) {
            throw new IdentityDomainException(ErrorCode.INVALID_PROFILE_IMAGE_URL,
                    "Profile image URL must not use javascript: or data: schemes");
        }
        try {
            URL parsed = new URL(trimmed);
            if (!"https".equals(parsed.getProtocol())) {
                throw new IdentityDomainException(ErrorCode.INVALID_PROFILE_IMAGE_URL,
                        "Profile image URL must use HTTPS scheme");
            }
        } catch (MalformedURLException e) {
            throw new IdentityDomainException(ErrorCode.INVALID_PROFILE_IMAGE_URL,
                    "Profile image URL is malformed: " + trimmed);
        }
        return new ProfileImageUrl(trimmed);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProfileImageUrl that)) return false;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
