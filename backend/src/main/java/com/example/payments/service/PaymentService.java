package com.example.payments.service;

import com.example.payments.api.dto.CreatePaymentRequest;
import com.example.payments.domain.Payment;
import com.example.payments.domain.PaymentRepository;
import com.example.payments.provider.PaymentProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9._:+/=-]{1,128}");

    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public PaymentService(PaymentRepository payments, PaymentProvider provider,
                          ApplicationEventPublisher events, Clock clock,
                          PlatformTransactionManager transactionManager) {
        this.payments = payments;
        this.provider = provider;
        this.events = events;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates a payment. With an Idempotency-Key, a retry of the same request returns the
     * original payment instead of charging again; the same key with different data is a
     * conflict. The unique key column is the backstop for two simultaneous first attempts.
     */
    public CreatePaymentResult create(CreatePaymentRequest request, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        String requestHash = key == null ? null : fingerprint(request);

        if (key != null) {
            Optional<Payment> existing = payments.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), requestHash);
            }
        }

        try {
            Payment payment = transactions.execute(status -> createNew(request, key, requestHash));
            log.info("payment created paymentId={} providerPaymentId={} amount={} currency={}",
                    payment.getId(), payment.getProviderPaymentId(),
                    payment.getAmount(), payment.getCurrency());
            return new CreatePaymentResult(payment, false);
        } catch (DataIntegrityViolationException e) {
            if (key == null) {
                throw e;
            }
            // The other request with this key won the insert race and is now committed.
            Optional<Payment> winner = payments.findByIdempotencyKey(key);
            if (winner.isEmpty()) {
                throw e;
            }
            return replay(winner.get(), requestHash);
        }
    }

    private Payment createNew(CreatePaymentRequest request, String idempotencyKey,
                              String idempotencyRequestHash) {
        String currency = request.currency().toUpperCase(Locale.ROOT);
        String providerPaymentId = provider.authorize(request.amount(), currency, idempotencyKey);

        Payment payment = new Payment(
                UUID.randomUUID(),
                request.amount().setScale(2),
                currency,
                request.customerReference(),
                providerPaymentId,
                clock.instant(),
                idempotencyKey,
                idempotencyRequestHash);
        // Flush now so a duplicate idempotency key fails inside this transaction instead of
        // surfacing later as an unclear commit-time error.
        payments.saveAndFlush(payment);

        // Delivered after commit, so the fake provider can never call back for a payment
        // that has not been committed yet.
        events.publishEvent(new PaymentCreatedEvent(payment.getId(), providerPaymentId));
        return payment;
    }

    private CreatePaymentResult replay(Payment payment, String requestHash) {
        if (!payment.getIdempotencyRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException();
        }
        log.info("payment idempotent replay paymentId={}", payment.getId());
        return new CreatePaymentResult(payment, true);
    }

    private String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        return idempotencyKey;
    }

    /** Amount is normalised to 2 decimals so 10.0 and 10.00 count as the same request. */
    private String fingerprint(CreatePaymentRequest request) {
        String canonical = request.amount().setScale(2).toPlainString()
                + "|" + request.currency().toUpperCase(Locale.ROOT)
                + "|" + request.customerReference();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Transactional(readOnly = true)
    public Payment findById(UUID id) {
        return payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
