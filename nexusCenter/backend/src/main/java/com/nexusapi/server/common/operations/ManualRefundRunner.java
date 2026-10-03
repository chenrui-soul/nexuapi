package com.nexusapi.server.common.operations;

import com.nexusapi.server.modules.billing.service.BillingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Internal, opt-in operational command for an audited BillingService refund.
 * It is unavailable unless the container is explicitly started with
 * nexus.operations.manual-refund.enabled=true, then exits after one operation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "nexus.operations.manual-refund", name = "enabled", havingValue = "true")
public class ManualRefundRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ManualRefundRunner.class);

    private final BillingService billingService;
    private final ConfigurableApplicationContext context;
    private final Environment environment;

    public ManualRefundRunner(
            BillingService billingService,
            ConfigurableApplicationContext context,
            Environment environment
    ) {
        this.billingService = billingService;
        this.context = context;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String requestId = required("nexus.operations.manual-refund.request-id");
        UUID userId = UUID.fromString(required("nexus.operations.manual-refund.user-id"));
        UUID reservationId = UUID.fromString(required("nexus.operations.manual-refund.reservation-id"));
        BigDecimal amount = new BigDecimal(required("nexus.operations.manual-refund.amount"));
        String idempotencyKey = required("nexus.operations.manual-refund.idempotency-key");
        String reason = required("nexus.operations.manual-refund.reason");

        BillingService.RefundResult result = billingService.refund(
                userId,
                reservationId,
                amount,
                idempotencyKey,
                reason,
                Map.of("operation", "manual_correction", "request_id", requestId)
        );
        log.info("Manual refund completed: requestId={}, reservationId={}, amount={}, replayed={}",
                requestId, reservationId, result.amount(), result.replayed());
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required manual refund property: " + name);
        }
        return value.trim();
    }
}
