package com.dsports.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
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
                    .description("JWT Bearer token obtained from /api/auth/login"))
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
                new Tag().name("Authentication").description("Registration, login, token refresh, logout"),
                new Tag().name("Customer Profile").description("Customer profile management"),
                new Tag().name("Addresses").description("Customer address management"),
                new Tag().name("Catalog").description("Product catalog, search, categories, brands"),
                new Tag().name("Inventory").description("Inventory availability lookup"),
                new Tag().name("Pricing").description("Price lookup"),
                new Tag().name("Admin").description("Administrative operations for catalog, inventory, and pricing"),
                new Tag().name("Actuator").description("Application health and monitoring")
            ));
    }

    @Bean
    public GroupedOpenApi allApi() {
        return GroupedOpenApi.builder()
            .group("all")
            .displayName("All APIs")
            .pathsToMatch("/api/**")
            .build();
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
    public GroupedOpenApi authenticationApi() {
        return GroupedOpenApi.builder()
            .group("authentication")
            .displayName("Authentication")
            .pathsToMatch("/api/auth/**")
            .build();
    }

    @Bean
    public GroupedOpenApi customerProfileApi() {
        return GroupedOpenApi.builder()
            .group("customer-profile")
            .displayName("Customer Profile")
            .pathsToMatch("/api/customers/**")
            .pathsToExclude("/api/customers/me/addresses/**")
            .build();
    }

    @Bean
    public GroupedOpenApi addressesApi() {
        return GroupedOpenApi.builder()
            .group("addresses")
            .displayName("Addresses")
            .pathsToMatch("/api/customers/me/addresses/**")
            .build();
    }

    @Bean
    public GroupedOpenApi catalogApi() {
        return GroupedOpenApi.builder()
            .group("catalog")
            .displayName("Catalog")
            .pathsToMatch("/api/catalog/**")
            .build();
    }

    @Bean
    public GroupedOpenApi inventoryApi() {
        return GroupedOpenApi.builder()
            .group("inventory")
            .displayName("Inventory")
            .pathsToMatch("/api/inventory/**")
            .build();
    }

    @Bean
    public GroupedOpenApi pricingApi() {
        return GroupedOpenApi.builder()
            .group("pricing")
            .displayName("Pricing")
            .pathsToMatch("/api/prices/**")
            .build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
            .group("admin")
            .displayName("Admin")
            .pathsToMatch("/api/admin/**")
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
