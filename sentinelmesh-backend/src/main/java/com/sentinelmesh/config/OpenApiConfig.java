package com.sentinelmesh.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sentinelMeshOpenApi() {
        SecurityScheme apiKey = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-API-Key");
        return new OpenAPI()
                .info(new Info()
                        .title("SentinelMesh API")
                        .version("0.1.0")
                        .description("Runtime security mesh for autonomous AI agents.")
                        .contact(new Contact().name("SentinelMesh Team")))
                .components(new Components().addSecuritySchemes("ApiKey", apiKey))
                .addSecurityItem(new SecurityRequirement().addList("ApiKey"));
    }
}
