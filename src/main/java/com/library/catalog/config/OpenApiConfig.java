package com.library.catalog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI libraryOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Library Catalog API")
                .description("REST API for managing a library catalog with books, authors, "
                    + "full-text search, and reservation system with concurrency control.")
                .version("1.0.0"));
    }
}
