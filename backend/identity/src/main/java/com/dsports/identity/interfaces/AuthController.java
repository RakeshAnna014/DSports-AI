package com.dsports.identity.interfaces;

import com.dsports.identity.application.command.LoginUserCommand;
import com.dsports.identity.application.command.LogoutCommand;
import com.dsports.identity.application.command.RefreshTokenCommand;
import com.dsports.identity.application.usecase.LoginUseCase;
import com.dsports.identity.application.usecase.LogoutUseCase;
import com.dsports.identity.application.usecase.RefreshTokenUseCase;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.interfaces.dto.ErrorResponse;
import com.dsports.identity.interfaces.dto.LoginResponse;
import com.dsports.identity.interfaces.dto.RefreshResponse;
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
                                .body(new ErrorResponse("Invalid email or password"));
                    }
                    return ResponseEntity.ok(new LoginResponse(
                            result.userId(), result.email(), result.roles(),
                            result.accessToken(), result.refreshToken()
                    ));
                });
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<Object>> refresh(@Valid @RequestBody RefreshTokenCommand command) {
        return refreshTokenUseCase.execute(command)
                .map(result -> {
                    if (!result.success()) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(new ErrorResponse("Invalid refresh token"));
                    }
                    return ResponseEntity.ok(new RefreshResponse(
                            result.accessToken(), result.refreshToken()
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
