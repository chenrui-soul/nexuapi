package com.nexusapi.server.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI nexusOpenApi() {
        return new OpenAPI().info(new Info()
                .title("NEXUS API Server")
                .version("0.1.0")
                .description("NEXUS API 控制台与 OpenAI 兼容网关接口"));
    }
}

