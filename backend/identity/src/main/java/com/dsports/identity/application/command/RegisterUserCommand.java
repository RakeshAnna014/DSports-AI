package com.dsports.identity.application.command;

public record RegisterUserCommand(
    String email,
    String password,
    String firstName,
    String lastName
) {}
