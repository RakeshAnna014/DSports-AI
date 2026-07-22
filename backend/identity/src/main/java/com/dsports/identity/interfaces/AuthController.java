package com.dsports.identity.interfaces;

import com.dsports.identity.application.command.LoginUserCommand;
import com.dsports.identity.application.command.LogoutCommand;
import com.dsports.identity.application.command.RefreshTokenCommand;
import com.dsports.identity.application.command.RegisterUserCommand;
import com.dsports.identity.application.usecase.LoginUseCase;
import com.dsports.identity.application.usecase.LogoutUseCase;
import com.dsports.identity.application.usecase.RefreshTokenUseCase;
import com.dsports.identity.application.usecase.RegisterUserUseCase;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.interfaces.dto.ErrorResponse;
import com.dsports.identity.interfaces.dto.LoginResponse;
import com.dsports.identity.interfaces.dto.RefreshResponse;
import com.dsports.identity.interfaces.dto.RegisterRequest;
import com.dsports.identity.interfaces.dto.RegisterResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Authentication")
public class AuthController {

    private final RegisterUserUseCase registerUserUseCase;
    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;

    public AuthController(RegisterUserUseCase registerUserUseCase, LoginUseCase loginUseCase,
                          RefreshTokenUseCase refreshTokenUseCase, LogoutUseCase logoutUseCase) {
        this.registerUserUseCase = registerUserUseCase;
        this.loginUseCase = loginUseCase;
        this.refreshTokenUseCase = refreshTokenUseCase;
        this.logoutUseCase = logoutUseCase;
    }

    @PostMapping("/register")
    @Operation(summary = "Register new user", description = "Create a new customer account with email, password, and personal details")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Account created successfully",
            content = @Content(schema = @Schema(implementation = RegisterResponse.class))),
        @ApiResponse(responseCode = "409", description = "Email already registered",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Mono<ResponseEntity<Object>> register(@Valid @RequestBody RegisterRequest request) {
        var command = new RegisterUserCommand(request.email(), request.password(), request.firstName(), request.lastName());
        return registerUserUseCase.execute(command)
                .map(result -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(new RegisterResponse(result.userId(), result.email())));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate user", description = "Login with email and password to receive JWT access and refresh tokens")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login successful",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "401", description = "Invalid email or password",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
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
    @Operation(summary = "Refresh access token", description = "Exchange a valid refresh token for a new access and refresh token pair")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Token refreshed successfully",
            content = @Content(schema = @Schema(implementation = RefreshResponse.class))),
        @ApiResponse(responseCode = "401", description = "Invalid refresh token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
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
    @Operation(summary = "Logout", description = "Invalidate the refresh token to end the session")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Logged out successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public Mono<ResponseEntity<Void>> logout(@Valid @RequestBody LogoutCommand command,
                                              Authentication authentication) {
        var userId = UserId.fromString(authentication.getPrincipal().toString());
        var authCommand = new LogoutCommand(command.refreshToken(), userId);
        return logoutUseCase.execute(authCommand)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
