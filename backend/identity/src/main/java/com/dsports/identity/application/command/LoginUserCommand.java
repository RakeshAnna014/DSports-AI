package com.dsports.identity.application.command;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginUserCommand(
    @NotBlank @Email String email,
    @NotBlank String password,
    String deviceName,
    String userAgent,
    String ipAddress
) {
    public LoginUserCommand withRequestMetadata(String userAgent, String ipAddress) {
        return new LoginUserCommand(this.email, this.password, this.deviceName, userAgent, ipAddress);
    }
}
