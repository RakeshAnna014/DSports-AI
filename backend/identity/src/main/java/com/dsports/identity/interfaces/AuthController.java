package com.dsports.identity.interfaces;

import com.dsports.identity.application.command.LoginUserCommand;
import com.dsports.identity.application.command.LogoutCommand;
import com.dsports.identity.application.command.RefreshTokenCommand;
import com.dsports.identity.application.usecase.LoginUseCase;
import com.dsports.identity.application.usecase.LogoutUseCase;
import com.dsports.identity.application.usecase.RefreshTokenUseCase;
import com.dsports.identity.domain.model.UserId;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
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
    public Mono<ResponseEntity<Object>> login(@Valid @RequestBody LoginUserCommand command,
                                               ServerHttpRequest request) {
        var userAgent = request.getHeaders().getFirst(HttpHeaders.USER_AGENT);
        var ipAddress = request.getRemoteAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : null;
        var enrichedCommand = command.withRequestMetadata(userAgent, ipAddress);

        return loginUseCase.execute(enrichedCommand)
                .map(result -> {
                    if (!result.success()) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(Map.of("message", "Invalid email or password"));
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
    public Mono<ResponseEntity<Object>> refresh(@Valid @RequestBody RefreshTokenCommand command) {
        return refreshTokenUseCase.execute(command)
                .map(result -> {
                    if (!result.success()) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(Map.of("message", "Invalid refresh token"));
                    }
                    return ResponseEntity.ok(Map.of(
                            "accessToken", result.accessToken(),
                            "refreshToken", result.refreshToken()
                    ));
                });
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(@Valid @RequestBody LogoutCommand command,
                                              Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        var authCommand = new LogoutCommand(command.refreshToken(), userId);
        return logoutUseCase.execute(authCommand)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
