package com.dsports.identity.application.command;

public record LogoutCommand(
    String refreshToken
) {}
