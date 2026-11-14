package com.climind.saas.subscriptions;

import com.climind.saas.shared.persistence.AssignedIdEntity;
import com.climind.saas.shared.util.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Suscripción paga de un negocio (contrato). Guarda una copia del precio y los límites del plan al contratar.
 * <p>
 * Estados internos (el proveedor tiene los suyos; el mapeo está en {@link SubscriptionSync}):
 * <ul>
 *   <li>PENDING: checkout iniciado, todavía sin un pago aprobado. Sin beneficios.</li>
 *   <li>ACTIVE: pago aprobado; beneficios hasta {@code currentPeriodEnd} (se renueva con cada cobro).</li>
 *   <li>PAST_DUE: cobro rechazado; beneficios durante la gracia ({@code graceUntil}).</li>
 *   <li>PAUSED: pausada en el proveedor; sin beneficios.</li>
 *   <li>CANCELED: cancelada; conserva los beneficios hasta el fin del período ya pagado.</li>
 *   <li>EXPIRED: terminó (gracia vencida o checkout abandonado). Rige el plan gratuito.</li>
 *   <li>TRIALING: reservado (hoy no hay prueba gratuita: el plan Gratis cumple ese rol).</li>
 * </ul>
 */
@Entity
@Table(name = "tenant_subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantSubscription extends AssignedIdEntity {

    public enum Status { PENDING, TRIALING, ACTIVE, PAST_DUE, PAUSED, CANCELED, EXPIRED }

    public enum Provider { MERCADOPAGO, FAKE }

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Provider provider;

    @Column(name = "external_id", length = 80)
    private String externalId;

    @Column(name = "checkout_url", length = 500)
    private String checkoutUrl;

    @Column(name = "plan_code", nullable = false, length = 30, updatable = false)
    private String planCode;

    @Column(name = "plan_name", nullable = false, length = 60, updatable = false)
    private String planName;

    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal price;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, length = 10, updatable = false)
    private SubscriptionPlan.BillingPeriod billingPeriod;

    @Column(name = "max_published_calculators", nullable = false, updatable = false)
    private int maxPublishedCalculators;

    @Column(name = "max_monthly_quotes", nullable = false, updatable = false)
    private int maxMonthlyQuotes;

    @Column(name = "csv_export", nullable = false, updatable = false)
    private boolean csvExport;

    @Column(name = "custom_branding", nullable = false, updatable = false)
    private boolean customBranding;

    @Column(name = "lead_email_notifications", nullable = false, updatable = false)
    private boolean leadEmailNotifications;

    @Column(name = "provider_status", length = 30)
    private String providerStatus;

    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "grace_until")
    private Instant graceUntil;

    @Column(name = "last_payment_at")
    private Instant lastPaymentAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "end_reason", length = 40)
    private String endReason;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    TenantSubscription(UUID tenantId, SubscriptionPlan plan, Provider provider, Instant now) {
        super(Ids.newId());
        this.tenantId = tenantId;
        this.planId = plan.getId();
        this.provider = provider;
        this.planCode = plan.getCode();
        this.planName = plan.getName();
        this.price = plan.getPrice();
        this.currency = plan.getCurrency();
        this.billingPeriod = plan.getBillingPeriod();
        this.maxPublishedCalculators = plan.getMaxPublishedCalculators();
        this.maxMonthlyQuotes = plan.getMaxMonthlyQuotes();
        this.csvExport = plan.isCsvExport();
        this.customBranding = plan.isCustomBranding();
        this.leadEmailNotifications = plan.isLeadEmailNotifications();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** ¿Da los beneficios del plan pago en este momento? */
    public boolean grantsBenefits(Instant now) {
        return switch (status) {
            case ACTIVE, TRIALING -> currentPeriodEnd == null || now.isBefore(currentPeriodEnd.plus(java.time.Duration.ofDays(3)));
            case PAST_DUE -> graceUntil != null && now.isBefore(graceUntil);
            case CANCELED -> currentPeriodEnd != null && now.isBefore(currentPeriodEnd);
            default -> false;
        };
    }

    public boolean isOpen() {
        return status == Status.PENDING || status == Status.ACTIVE || status == Status.PAST_DUE
                || status == Status.PAUSED || status == Status.TRIALING;
    }

    void checkoutCreated(String externalId, String checkoutUrl, String providerStatus, Instant now) {
        this.externalId = externalId;
        this.checkoutUrl = checkoutUrl;
        this.providerStatus = providerStatus;
        this.updatedAt = now;
    }

    void providerStatus(String providerStatus, Instant now) {
        this.providerStatus = providerStatus;
        this.lastSyncedAt = now;
        this.updatedAt = now;
    }

    /** Pago aprobado: activa (o renueva) el período pagado. Nunca acorta un período ya pagado. */
    void paymentApproved(Instant paidAt, Instant periodStart, Instant periodEnd, Instant now) {
        this.status = Status.ACTIVE;
        this.graceUntil = null;
        if (lastPaymentAt == null || paidAt.isAfter(lastPaymentAt)) {
            this.lastPaymentAt = paidAt;
        }
        if (currentPeriodEnd == null || periodEnd.isAfter(currentPeriodEnd)) {
            this.currentPeriodStart = periodStart;
            this.currentPeriodEnd = periodEnd;
        }
        this.checkoutUrl = null;
        this.updatedAt = now;
    }

    void pastDue(Instant graceUntil, Instant now) {
        this.status = Status.PAST_DUE;
        if (this.graceUntil == null) {
            this.graceUntil = graceUntil;
        }
        this.updatedAt = now;
    }

    void paused(Instant now) {
        this.status = Status.PAUSED;
        this.updatedAt = now;
    }

    void resumed(Instant now) {
        this.status = Status.ACTIVE;
        this.updatedAt = now;
    }

    void canceled(String reason, Instant now) {
        if (status == Status.PENDING) {
            end(reason, now);
            return;
        }
        this.status = Status.CANCELED;
        this.canceledAt = now;
        this.endReason = reason;
        this.checkoutUrl = null;
        this.updatedAt = now;
    }

    /**
     * Termina la suscripción: reemplazada por otro plan (los beneficios pasan al plan nuevo ya mismo), gracia
     * vencida, pausa que no se retomó o checkout abandonado.
     */
    void end(String reason, Instant now) {
        boolean hadBenefits = status == Status.ACTIVE || status == Status.TRIALING || status == Status.CANCELED;
        if (hadBenefits) {
            this.status = Status.CANCELED;
            if (canceledAt == null) {
                this.canceledAt = now;
            }
            if (currentPeriodEnd == null || currentPeriodEnd.isAfter(now)) {
                this.currentPeriodEnd = now;
            }
        } else {
            this.status = Status.EXPIRED;
        }
        this.endedAt = now;
        this.endReason = reason;
        this.checkoutUrl = null;
        this.updatedAt = now;
    }
}
