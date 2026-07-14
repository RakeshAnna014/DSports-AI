package com.dsports.identity.interfaces;

import com.dsports.identity.application.command.LoginUserCommand;
import com.dsports.identity.application.command.LogoutCommand;
import com.dsports.identity.application.command.RefreshTokenCommand;
import com.dsports.identity.application.usecase.LoginUseCase;
import com.dsports.identity.application.usecase.LogoutUseCase;
import com.dsports.identity.application.usecase.RefreshTokenUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;

    public AuthController(LoginUseCase loginUseCase, RefreshTokenUseCase refreshTokenUseCase,
                          LogoutUseCase logoutUseCase) {
        this.loginUseCase = loginUseCase;
        this.refreshTokenUseCase = refreshTokenUseCase;
        this.logoutUseCase = logoutUseCase;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<Object>> login(@RequestBody LoginUserCommand command) {
        return Mono.fromCallable(() -> {
            var result = loginUseCase.execute(command);
            if (!result.success()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid credentials", "reason", result.failureReason().name()));
            }
            return ResponseEntity.ok(Map.of(
                    "userId", result.userId(),
                    "email", result.email(),
                    "roles", result.roles(),
                    "accessToken", result.accessToken(),
                    "refreshToken", result.refreshToken()
            ));
        });
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<Object>> refresh(@RequestBody RefreshTokenCommand command) {
        return Mono.fromCallable(() -> {
            var result = refreshTokenUseCase.execute(command);
            if (!result.success()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid refresh token", "reason", result.failureReason()));
            }
            return ResponseEntity.ok(Map.of(
                    "accessToken", result.accessToken(),
                    "refreshToken", result.refreshToken()
            ));
        });
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(@RequestBody LogoutCommand command) {
        return Mono.fromRunnable(() -> logoutUseCase.execute(command))
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
