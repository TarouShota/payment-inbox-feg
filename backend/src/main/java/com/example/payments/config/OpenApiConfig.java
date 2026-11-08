package com.example.payments.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI paymentInboxOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Payment Inbox API")
                .version("1.0")
                .description("Operator payment API and provider callback endpoint. "
                        + "Callback authentication is configured separately."));
    }
}
