package com.example.payments.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Posts the fake provider's callback over real HTTP, exactly like an external provider would. */
@Component
public class HttpProviderCallbackClient implements ProviderCallbackClient {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderCallbackClient.class);

    private final RestClient restClient;
    private final ProviderProperties properties;

    public HttpProviderCallbackClient(RestClient.Builder restClientBuilder, ProviderProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    @Override
    public void send(ProviderCallback callback) {
        try {
            restClient.post()
                    .uri(properties.getCallbackUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(callback)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Fake provider delivered callback {} for {} ({})",
                    callback.eventId(), callback.providerPaymentId(), callback.status());
        } catch (Exception e) {
            log.warn("Fake provider failed to deliver callback {} for {}: {}",
                    callback.eventId(), callback.providerPaymentId(), e.toString());
        }
    }
}
