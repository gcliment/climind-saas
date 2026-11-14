package com.climind.saas.subscriptions;

import com.climind.saas.subscriptions.payments.PaymentProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tareas periódicas de facturación.
 * <ul>
 *   <li><b>Conciliación</b> (cada hora): consulta en el proveedor el estado real de cada suscripción abierta y de
 *       sus cobros. Si una notificación se perdió, acá se corrige, y la corrección queda registrada
 *       ({@code payment_provider_events} con resultado CORRECTED) para el panel de administración.</li>
 *   <li><b>Vencimientos</b>: PAST_DUE con la gracia vencida pasa a EXPIRED (rige el plan gratuito); un checkout
 *       que nunca se completó se descarta. Nunca se borran datos.</li>
 * </ul>
 */
@Slf4j
@Service
public class BillingJobs {

    private static final EnumSet<TenantSubscription.Status> OPEN = EnumSet.of(TenantSubscription.Status.PENDING,
            TenantSubscription.Status.ACTIVE, TenantSubscription.Status.PAST_DUE, TenantSubscription.Status.PAUSED);

    private final TenantSubscriptionRepository subscriptions;
    private final SubscriptionSync sync;
    private final PaymentProvider provider;
    private final BillingNotifier notifier;
    private final TransactionTemplate transactions;
    private final com.climind.saas.shared.config.AppProperties properties;
    private final Clock clock;

    public BillingJobs(TenantSubscriptionRepository subscriptions, SubscriptionSync sync, PaymentProvider provider,
                       BillingNotifier notifier, TransactionTemplate transactions,
                       com.climind.saas.shared.config.AppProperties properties, Clock clock) {
        this.subscriptions = subscriptions;
        this.sync = sync;
        this.provider = provider;
        this.notifier = notifier;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    public record Report(int checked, int corrected, int expired, int failed) {
    }

    @Scheduled(cron = "${app.payments.reconcile-cron:0 15 * * * *}")
    public void scheduled() {
        if (!provider.configured()) {
            return;
        }
        Report report = run();
        if (report.corrected() > 0 || report.expired() > 0 || report.failed() > 0) {
            log.info("Conciliación de pagos: {}", report);
        }
    }

    /** Conciliación + vencimientos. Cada suscripción en su propia transacción: una falla no frena al resto. */
    public Report run() {
        int checked = 0;
        int corrected = 0;
        int failed = 0;
        List<UUID> open = subscriptions.findByStatusIn(OPEN).stream()
                .filter(s -> s.getExternalId() != null && s.getProvider() == sync.providerType())
                .map(TenantSubscription::getId).toList();
        for (UUID id : open) {
            checked++;
            try {
                Boolean changed = transactions.execute(status -> reconcile(id));
                if (Boolean.TRUE.equals(changed)) {
                    corrected++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Conciliación: no se pudo verificar la suscripción {}: {}", id, e.getMessage());
            }
        }
        int expired = expire();
        Instant now = clock.instant();
        Report report = new Report(checked, corrected, expired, failed);
        transactions.executeWithoutResult(status -> sync.recordEvent("reconciliation-run:" + now.toEpochMilli(),
                "reconciliation_run", null, null, "PROCESSED",
                "Revisadas " + report.checked() + ", corregidas " + report.corrected() + ", vencidas "
                        + report.expired() + ", con error " + report.failed()));
        return report;
    }

    private boolean reconcile(UUID id) {
        TenantSubscription sub = subscriptions.findById(id).orElseThrow();
        String before = snapshot(sub);
        provider.fetchSubscription(sub.getExternalId()).ifPresent(r -> sync.applySubscriptionStatus(sub, r.status()));
        for (PaymentProvider.RemotePayment payment : provider.searchPayments(sub.getExternalId())) {
            sync.applyPayment(sub, payment);
        }
        String after = snapshot(sub);
        if (!before.equals(after)) {
            sync.recordEvent("reconciliation:" + sub.getId() + ":" + clock.instant().toEpochMilli(), "reconciliation",
                    sub.getExternalId(), null, "CORRECTED", before + " → " + after);
            return true;
        }
        return false;
    }

    private static String snapshot(TenantSubscription s) {
        return s.getStatus() + (s.getCurrentPeriodEnd() == null ? "" : " hasta " + s.getCurrentPeriodEnd());
    }

    private int expire() {
        Instant now = clock.instant();
        int count = 0;
        for (TenantSubscription sub : subscriptions.findByStatusAndGraceUntilBefore(TenantSubscription.Status.PAST_DUE, now)) {
            count += Objects.requireNonNullElse(transactions.execute(status -> {
                TenantSubscription s = subscriptions.findById(sub.getId()).orElseThrow();
                sync.cancelAtProvider(s);
                s.end("GRACE_EXPIRED", now);
                subscriptions.save(s);
                notifier.expired(s);
                return 1;
            }), 0);
        }
        Instant abandoned = now.minus(properties.getPayments().getPendingCheckoutTtl());
        for (TenantSubscription sub : subscriptions.findByStatusAndCreatedAtBefore(TenantSubscription.Status.PENDING, abandoned)) {
            transactions.executeWithoutResult(status -> {
                TenantSubscription s = subscriptions.findById(sub.getId()).orElseThrow();
                sync.cancelAtProvider(s);
                s.end("ABANDONED", now);
                subscriptions.save(s);
            });
        }
        return count;
    }
}
