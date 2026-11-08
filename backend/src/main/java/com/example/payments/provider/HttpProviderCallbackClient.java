package com.example.payments.provider;

import com.example.payments.security.CallbackSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Posts the fake provider's callback over real HTTP, exactly like an external provider would. */
@Component
public class HttpProviderCallbackClient implements ProviderCallbackClient {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderCallbackClient.class);

    private final RestClient restClient;
    private final ProviderProperties properties;
    private final ObjectMapper objectMapper;
    private final CallbackSigner signer;
    private final Clock clock;

    public HttpProviderCallbackClient(RestClient.Builder restClientBuilder, ProviderProperties properties,
                                      ObjectMapper objectMapper, CallbackSigner signer, Clock clock) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());

        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.signer = signer;
        this.clock = clock;
    }

    @Override
    public void send(ProviderCallback callback) {
        try {
            // Serialize once and sign these exact bytes. Serializing again would risk the body
            // and signature disagreeing on field order or formatting.
            String payload = objectMapper.writeValueAsString(callback);

            RestClient.RequestBodySpec request = restClient.post()
                    .uri(properties.getCallbackUrl())
                    .contentType(MediaType.APPLICATION_JSON);

            if (signer.isEnabled()) {
                long timestamp = clock.instant().getEpochSecond();
                request = request
                        .header(CallbackSigner.TIMESTAMP_HEADER, Long.toString(timestamp))
                        .header(CallbackSigner.SIGNATURE_HEADER, signer.sign(timestamp, payload));
            }

            request.body(payload).retrieve().toBodilessEntity();
            log.info("Fake provider delivered callback {} for {} ({})",
                    callback.eventId(), callback.providerPaymentId(), callback.status());
        } catch (Exception e) {
            log.warn("Fake provider failed to deliver callback {} for {}: {}",
                    callback.eventId(), callback.providerPaymentId(), e.toString());
        }
    }
}
