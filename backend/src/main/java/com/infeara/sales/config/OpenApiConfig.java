package com.infeara.sales.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Generated OpenAPI document — the API contract is always documented (FR/API standard). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI salesOsOpenApi() {
        final String scheme = "sessionCookie";
        return new OpenAPI()
                .info(new Info()
                        .title("Infeara Sales OS API")
                        .version("v1")
                        .description("""
                                Revenue-operations API. Conventions: base path /api/v1, UUID identifiers,
                                ISO-8601 UTC timestamps, standard {success, data|error, meta} envelope,
                                Idempotency-Key for non-repeatable commands, rowVersion for optimistic
                                concurrency. All business rules are enforced server-side.
                                """)
                        .contact(new Contact().name("Infeara Technologies — Platform Engineering"))
                        .license(new License().name("Proprietary — internal use only")))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("INF_SESSION")
                                .description("HttpOnly session cookie; mutations also require the X-XSRF-TOKEN header")))
                .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
