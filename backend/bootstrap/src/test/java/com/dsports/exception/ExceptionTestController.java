package com.dsports.exception;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test/errors")
public class ExceptionTestController {

    public record TestRequest(@NotBlank @Email String email, @NotBlank String name) {}

    @GetMapping("/duplicate-email")
    public void duplicateEmail() {
        throw new IdentityDomainException(ErrorCode.DUPLICATE_EMAIL, "Email already registered");
    }

    @GetMapping("/invalid-email")
    public void invalidEmail() {
        throw new IdentityDomainException(ErrorCode.INVALID_EMAIL, "Invalid email format");
    }

    @GetMapping("/user-not-found")
    public void userNotFound() {
        throw new IdentityDomainException(ErrorCode.USER_NOT_FOUND, "User not found");
    }

    @GetMapping("/invalid-status")
    public void invalidStatus() {
        throw new IdentityDomainException(ErrorCode.INVALID_STATUS_TRANSITION, "Cannot transition from DELETED to ACTIVE");
    }

    @GetMapping("/invalid-password")
    public void invalidPassword() {
        throw new IdentityDomainException(ErrorCode.INVALID_PASSWORD, "Invalid password");
    }

    @GetMapping("/account-locked")
    public void accountLocked() {
        throw new IdentityDomainException(ErrorCode.ACCOUNT_LOCKED, "Account is locked due to multiple failed attempts");
    }

    @GetMapping("/generic-error")
    public void genericError() {
        throw new IdentityDomainException(ErrorCode.GENERIC, "Something went wrong");
    }

    @GetMapping("/unexpected")
    public void unexpected() {
        throw new RuntimeException("Unexpected internal error");
    }

    @PostMapping("/validation")
    public void validation(@Valid @RequestBody TestRequest request) {
    }
}
