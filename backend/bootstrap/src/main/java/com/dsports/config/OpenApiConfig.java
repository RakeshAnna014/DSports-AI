package com.dsports.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearer-jwt";

    @Bean
    public OpenAPI dsportsOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("DSports-AI API")
                .description(
                    """
                    AI-powered sports e-commerce platform.
                    Enterprise-grade REST API for product catalog, orders, payments, and franchise management.
                    """.stripIndent()
                )
                .version("1.0.0")
                .contact(new Contact()
                    .name("DSports-AI Team")
                    .email("dev@dsports.ai")
                    .url("https://dsports.ai"))
                .license(new License()
                    .name("Proprietary")
                    .url("https://dsports.ai/license")))
            .addServersItem(new Server()
                .url("http://localhost:8080")
                .description("Local development"))
            .addServersItem(new Server()
                .url("https://dev.dsports.ai")
                .description("Development server"))
            .addServersItem(new Server()
                .url("https://api.dsports.ai")
                .description("Production server"))
            .addSecurityItem(new SecurityRequirement()
                .addList(SECURITY_SCHEME_NAME))
            .components(new Components()
                .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT Bearer token obtained from /api/v1/auth/login"))
                .addParameters("correlationId", new HeaderParameter()
                    .name("X-Correlation-Id")
                    .description("Unique request identifier for tracing")
                    .required(false)
                    .schema(new StringSchema().example("uuid")))
                .addResponses("400", new ApiResponse()
                    .description("Bad request — validation error"))
                .addResponses("401", new ApiResponse()
                    .description("Unauthorized — missing or invalid JWT"))
                .addResponses("403", new ApiResponse()
                    .description("Forbidden — insufficient permissions"))
                .addResponses("404", new ApiResponse()
                    .description("Resource not found"))
                .addResponses("409", new ApiResponse()
                    .description("Conflict — duplicate or state conflict"))
                .addResponses("500", new ApiResponse()
                    .description("Internal server error")))
            .tags(List.of(
                new Tag().name("Authentication").description("Login, registration, token refresh"),
                new Tag().name("Products").description("Product catalog, search, categories"),
                new Tag().name("Cart").description("Shopping cart management"),
                new Tag().name("Orders").description("Order placement and history"),
                new Tag().name("Payments").description("Payment processing"),
                new Tag().name("Admin").description("Administrative operations"),
                new Tag().name("Franchise").description("Franchise store management"),
                new Tag().name("Actuator").description("Application health and monitoring")
            ));
    }

    @Bean
    public GroupedOpenApi actuatorApi() {
        return GroupedOpenApi.builder()
            .group("actuator")
            .displayName("Actuator")
            .pathsToMatch("/actuator/**")
            .build();
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
            .group("public")
            .displayName("Public APIs")
            .pathsToMatch("/api/v1/public/**", "/api/v1/products/**", "/api/v1/categories/**")
            .build();
    }

    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
            .group("auth")
            .displayName("Authentication")
            .pathsToMatch("/api/v1/auth/**")
            .build();
    }

    @Bean
    public GroupedOpenApi cartApi() {
        return GroupedOpenApi.builder()
            .group("cart")
            .displayName("Cart")
            .pathsToMatch("/api/v1/cart/**")
            .build();
    }

    @Bean
    public GroupedOpenApi orderApi() {
        return GroupedOpenApi.builder()
            .group("orders")
            .displayName("Orders")
            .pathsToMatch("/api/v1/orders/**")
            .build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
            .group("admin")
            .displayName("Admin")
            .pathsToMatch("/api/v1/admin/**")
            .build();
    }

    @Bean
    public GroupedOpenApi franchiseApi() {
        return GroupedOpenApi.builder()
            .group("franchise")
            .displayName("Franchise")
            .pathsToMatch("/api/v1/franchise/**")
            .build();
    }

    @Bean
    public OpenApiCustomizer globalResponseHeaders() {
        return openApi -> openApi.getPaths().values().stream()
            .flatMap(pathItem -> pathItem.readOperations().stream())
            .forEach(operation -> {
                var responses = operation.getResponses();
                if (responses == null) {
                    operation.setResponses(new ApiResponses());
                }
            });
    }
}
