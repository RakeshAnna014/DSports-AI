package com.dsports.identity.domain.event;

import com.dsports.identity.domain.model.AuthenticationProvider;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.UserId;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class UserRegisteredEvent extends DomainEvent {

    private final UserId userId;
    private final Email email;
    private final AuthenticationProvider provider;

    public UserRegisteredEvent(UserId userId, Email email, AuthenticationProvider provider) {
        this.userId = userId;
        this.email = email;
        this.provider = provider;
    }

    public UserId userId() {
        return userId;
    }

    public Email email() {
        return email;
    }

    public AuthenticationProvider provider() {
        return provider;
    }
}
