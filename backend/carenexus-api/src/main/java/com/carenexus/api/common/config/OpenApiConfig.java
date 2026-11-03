package com.carenexus.api.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI metadata. Swagger UI and /v3/api-docs are disabled by default and enabled only in the
 * local/docker profiles (springdoc.*.enabled in application.yml).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("CareNexus API")
                .version("1.0.0")
                .description("CareNexus REST API for patients, doctors and proxy caregivers: authentication, "
                        + "profiles, appointments, consultations, prescriptions, lab and imaging results, "
                        + "medical records and caregiver access. All endpoints except /api/auth/login, "
                        + "/api/auth/register, /api/auth/refresh and /api/auth/logout require a bearer access token.")
            )
            .servers(List.of(new Server().url("/")))
            // Apply the bearer scheme to every operation so "Try it out" sends the token
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
            .components(new Components()
                .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Access token (accessToken) returned by POST /api/auth/login or /api/auth/refresh")
                )
            );
    }
}
