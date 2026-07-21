package com.dsports.security;

import com.dsports.identity.infrastructure.security.JwtAuthenticationConverter;
import com.dsports.identity.infrastructure.security.JwtAuthenticationManager;
import com.dsports.identity.infrastructure.security.JwtTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    private final JwtTokenProvider tokenProvider;

    public SecurityConfig(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        var authManager = new JwtAuthenticationManager(tokenProvider);
        var authConverter = new JwtAuthenticationConverter();

        var authFilter = new AuthenticationWebFilter(authManager);
        authFilter.setServerAuthenticationConverter(authConverter);
        authFilter.setRequiresAuthenticationMatcher(ServerWebExchangeMatchers.pathMatchers("/api/**"));
        authFilter.setAuthenticationFailureHandler((exchange, exception) -> {
            var response = exchange.getExchange().getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            var buffer = response.bufferFactory()
                    .wrap("{\"message\":\"Invalid or expired token\"}".getBytes());
            return response.writeWith(Mono.just(buffer));
        });

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/favicon.ico").permitAll()
                        .pathMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
                        .pathMatchers("/actuator/health").permitAll()
                        .pathMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**", "/api-docs/**").permitAll()
                        .pathMatchers("/api-docs/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/catalog/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/prices/**").permitAll()
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyExchange().authenticated()
                )
                .addFilterAt(authFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
