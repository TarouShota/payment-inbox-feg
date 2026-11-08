package com.example.payments.security;

import com.example.payments.api.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Optional callback authentication. When enabled, verifies the provider's timestamp and
 * HMAC signature before Spring reads the body, then hands the controller a request that can
 * still read the exact signed bytes. Disabled by default.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CallbackSignatureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CallbackSignatureFilter.class);
    private static final String CALLBACK_PATH = "/provider-callbacks";
    // Removes the context path and path parameters, so /provider-callbacks;foo cannot skip auth.
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final CallbackSigner signer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CallbackSignatureFilter(CallbackSigner signer, ObjectMapper objectMapper, Clock clock) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !signer.isEnabled()
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !CALLBACK_PATH.equals(PATH_HELPER.getPathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        String timestamp = request.getHeader(CallbackSigner.TIMESTAMP_HEADER);
        String signature = request.getHeader(CallbackSigner.SIGNATURE_HEADER);

        if (!signer.verify(timestamp, signature, new String(body, StandardCharsets.UTF_8),
                clock.instant())) {
            log.warn("callback rejected reason=invalid_signature");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(), ApiError.of(
                    "invalid_signature", "Callback signature is missing, stale or invalid"));
            return;
        }

        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    /** Replays the already-read bytes so request-body parsing works exactly as before. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // No async body reads in this synchronous endpoint.
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        private Charset charset() {
            String encoding = getCharacterEncoding();
            return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        }
    }
}
