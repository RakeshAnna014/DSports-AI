package com.dsports.identity.application.command;

public record LoginUserCommand(
    String email,
    String password
) {}
