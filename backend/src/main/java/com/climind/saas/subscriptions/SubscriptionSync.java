package com.climind.saas.subscriptions;

import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.util.Ids;
import com.climind.saas.subscriptions.PaymentTransaction.Status;
import com.climind.saas.subscriptions.payments.PaymentProvider;
import com.climind.saas.subscriptions.payments.PaymentProvider.RemotePayment;
import com.climind.saas.subscriptions.payments.PaymentProvider.RemoteSubscription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;

/**
 * Aplica el estado REAL del proveedor (consultado servidor a servidor) a las suscripciones y pagos locales.
 * Lo usan las notificaciones (webhooks) y la conciliación periódica, así que:
 * <ul>
 *   <li><b>Idempotente</b>: aplicar dos veces el mismo estado no cambia nada ni duplica pagos (únicos por id
 *       externo).</li>
 *   <li><b>Independiente del orden</b>: nunca se usa el contenido de la notificación, se consulta el recurso;
 *       un pago aprobado nunca acorta un período ya pagado y un rechazo viejo no pisa un pago posterior.</li>
 * </ul>
 * Mapeo de estados del proveedor (Mercado Pago):
 * <pre>
 *  suscripción  pending → PENDING (sin beneficios)   authorized → espera el primer pago aprobado
 *               paused  → PAUSED                     cancelled  → CANCELED (beneficios hasta fin del período)
 *  pago         approved → APPROVED → ACTIVE (+1 período)   rejected → REJECTED → PAST_DUE (gracia de N días)
 *               refunded/charged_back → REFUNDED     cancelled → CANCELLED     el resto → PENDING
 * </pre>
 */
@Slf4j
@Service
public class SubscriptionSync {

    private static final EnumSet<TenantSubscription.Status> CURRENT = EnumSet.of(TenantSubscription.Status.ACTIVE,
            TenantSubscription.Status.PAST_DUE, TenantSubscription.Status.PAUSED, TenantSubscription.Status.TRIALING);

    private final TenantSubscriptionRepository subscriptions;
    private final PaymentTransactionRepository payments;
    private final PaymentProvider provider;
    private final BillingNotifier notifier;
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final Clock clock;

    public SubscriptionSync(TenantSubscriptionRepository subscriptions, PaymentTransactionRepository payments,
                            PaymentProvider provider, BillingNotifier notifier, JdbcTemplate jdbc,
                            AppProperties properties, Clock clock) {
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.provider = provider;
        this.notifier = notifier;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    TenantSubscription.Provider providerType() {
        return TenantSubscription.Provider.valueOf(provider.name());
    }

    // ------------------------------------------------------------------ notificaciones

    /**
     * Procesa una notificación ya verificada. Registra el evento (único por proveedor + clave): si ya se
     * procesó, no hace nada.
     *
     * @return resultado registrado, o "DUPLICATE" si ya se había procesado
     */
    @Transactional
    public String handleNotification(String eventKey, String type, String resourceId, String payload) {
        if (alreadyProcessed(eventKey)) {
            return "DUPLICATE";
        }
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
        String result = switch (normalized) {
            case "subscription_preapproval", "preapproval" -> syncSubscription(resourceId) ? "PROCESSED" : "IGNORED";
            case "subscription_authorized_payment", "authorized_payment" -> syncPayment(resourceId) ? "PROCESSED" : "IGNORED";
            default -> "IGNORED";
        };
        int inserted = recordEvent(eventKey, normalized.isEmpty() ? "unknown" : normalized, resourceId, payload, result,
                null);
        if (inserted == 0) {
            // Ya estaba registrada: la sincronización es idempotente, así que no hubo efectos nuevos.
            return "DUPLICATE";
        }
        return result;
    }

    int recordEvent(String eventKey, String type, String resourceId, String payload, String result, String detail) {
        return jdbc.update("""
                        INSERT INTO payment_provider_events (id, provider, event_key, event_type, resource_id, payload,
                                                             result, detail, received_at)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        ON CONFLICT (provider, event_key) DO NOTHING
                        """, Ids.newId(), provider.name(), truncate(eventKey, 120), truncate(type, 60),
                truncate(resourceId, 80), payload == null || payload.isBlank() ? "{}" : payload, result,
                truncate(detail, 500), Timestamp.from(clock.instant()));
    }

    boolean alreadyProcessed(String eventKey) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM payment_provider_events WHERE provider = ? AND event_key = ?", Integer.class,
                provider.name(), eventKey);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------ suscripción

    @Transactional
    public boolean syncSubscription(String externalId) {
        Optional<TenantSubscription> local = subscriptions.findByProviderAndExternalId(providerType(), externalId);
        if (local.isEmpty()) {
            return false;
        }
        Optional<RemoteSubscription> remote = provider.fetchSubscription(externalId);
        remote.ifPresent(r -> applySubscriptionStatus(local.get(), r.status()));
        return remote.isPresent();
    }

    void applySubscriptionStatus(TenantSubscription sub, String remoteStatus) {
        Instant now = clock.instant();
        String status = remoteStatus == null ? "" : remoteStatus.toLowerCase(Locale.ROOT);
        sub.providerStatus(status, now);
        switch (status) {
            case "authorized" -> {
                if (sub.getStatus() == TenantSubscription.Status.PAUSED) {
                    sub.resumed(now);
                }
                // Si está PENDING, se activa recién con el primer pago aprobado.
            }
            case "paused" -> {
                if (sub.getStatus() == TenantSubscription.Status.ACTIVE
                        || sub.getStatus() == TenantSubscription.Status.PAST_DUE) {
                    sub.paused(now);
                }
            }
            case "cancelled", "canceled" -> {
                if (sub.isOpen()) {
                    boolean hadBenefits = sub.grantsBenefits(now);
                    sub.canceled("PROVIDER", now);
                    if (hadBenefits) {
                        notifier.canceled(sub);
                    }
                }
            }
            default -> {
                // pending u otros: sin cambios de beneficios
            }
        }
        subscriptions.save(sub);
    }

    // ------------------------------------------------------------------ pagos

    @Transactional
    public boolean syncPayment(String paymentExternalId) {
        Optional<RemotePayment> remote = provider.fetchPayment(paymentExternalId);
        if (remote.isEmpty() || remote.get().subscriptionExternalId() == null) {
            return false;
        }
        Optional<TenantSubscription> sub = subscriptions.findByProviderAndExternalId(providerType(),
                remote.get().subscriptionExternalId());
        sub.ifPresent(s -> applyPayment(s, remote.get()));
        return sub.isPresent();
    }

    void applyPayment(TenantSubscription sub, RemotePayment remote) {
        Instant now = clock.instant();
        PaymentTransaction tx = payments.findByProviderAndExternalId(sub.getProvider(), remote.externalId())
                .orElseGet(() -> new PaymentTransaction(sub, remote.externalId(), now));
        Status previous = tx.isNew() ? null : tx.getStatus();
        Status status = map(remote.paymentStatus());
        tx.update(status, remote.paymentStatus() == null ? remote.chargeStatus() : remote.paymentStatus(),
                remote.amount(), remote.chargeDate(), status == Status.APPROVED ? orNow(remote.paidAt(), now) : null, now);
        payments.save(tx);

        if (status == Status.APPROVED && previous != Status.APPROVED) {
            approved(sub, tx, now);
        } else if (status == Status.REJECTED && previous != Status.REJECTED) {
            rejected(sub, tx, now);
        }
    }

    private void approved(TenantSubscription sub, PaymentTransaction tx, Instant now) {
        if (sub.getStatus() == TenantSubscription.Status.CANCELED || sub.getStatus() == TenantSubscription.Status.EXPIRED) {
            // Cobro tardío de una suscripción ya terminada: queda registrado para conciliar, sin reactivarla.
            log.warn("Pago aprobado {} de una suscripción terminada {}", tx.getExternalId(), sub.getId());
            return;
        }
        boolean activation = sub.getStatus() != TenantSubscription.Status.ACTIVE;
        if (activation) {
            // Al activarse el plan nuevo, el anterior (si lo había) se termina: un solo plan vigente por negocio.
            subscriptions.findByTenantIdAndStatusIn(sub.getTenantId(), CURRENT).stream()
                    .filter(other -> !other.getId().equals(sub.getId()))
                    .forEach(other -> {
                        cancelAtProvider(other);
                        other.end("REPLACED", now);
                        subscriptions.saveAndFlush(other);
                    });
        }
        Instant paidAt = tx.getPaidAt() == null ? now : tx.getPaidAt();
        Instant start = sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isAfter(paidAt.minus(Duration.ofDays(5)))
                && !activation ? sub.getCurrentPeriodEnd() : paidAt;
        Instant end = start.atOffset(ZoneOffset.UTC)
                .plusMonths(sub.getBillingPeriod() == SubscriptionPlan.BillingPeriod.YEARLY ? 12 : 1).toInstant();
        sub.paymentApproved(paidAt, start, end, now);
        subscriptions.saveAndFlush(sub);
        if (activation) {
            notifier.activated(sub);
        }
    }

    private void rejected(TenantSubscription sub, PaymentTransaction tx, Instant now) {
        boolean newerThanLastPayment = sub.getLastPaymentAt() == null || tx.getChargeDate() == null
                || !tx.getChargeDate().isBefore(sub.getLastPaymentAt());
        if (sub.getStatus() == TenantSubscription.Status.ACTIVE && newerThanLastPayment) {
            sub.pastDue(now.plus(Duration.ofDays(properties.getPayments().getGraceDays())), now);
            subscriptions.save(sub);
            notifier.paymentFailed(sub);
        }
        // PENDING (primer cobro rechazado): sigue sin beneficios; Mercado Pago reintenta.
    }

    /** Baja en el proveedor sin bloquear el flujo local si el proveedor no responde (la conciliación reintenta). */
    void cancelAtProvider(TenantSubscription sub) {
        if (sub.getExternalId() == null) {
            return;
        }
        try {
            provider.cancelSubscription(sub.getExternalId());
        } catch (RuntimeException e) {
            log.warn("No se pudo cancelar la suscripción {} en el proveedor: {}", sub.getId(), e.getMessage());
        }
    }

    static Status map(String paymentStatus) {
        if (paymentStatus == null) {
            return Status.PENDING;
        }
        return switch (paymentStatus.toLowerCase(Locale.ROOT)) {
            case "approved", "accredited" -> Status.APPROVED;
            case "rejected" -> Status.REJECTED;
            case "refunded", "charged_back" -> Status.REFUNDED;
            case "cancelled", "canceled" -> Status.CANCELLED;
            default -> Status.PENDING;
        };
    }

    private static Instant orNow(Instant value, Instant now) {
        return value == null ? now : value;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
