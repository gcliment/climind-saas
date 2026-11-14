package com.climind.saas.subscriptions;

import com.climind.saas.shared.persistence.AssignedIdEntity;
import com.climind.saas.shared.util.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Cobro de una suscripción, tal como lo informa el proveedor. Único por (proveedor, id externo). */
@Entity
@Table(name = "payment_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction extends AssignedIdEntity {

    public enum Status { PENDING, APPROVED, REJECTED, REFUNDED, CANCELLED }

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TenantSubscription.Provider provider;

    @Column(name = "external_id", nullable = false, length = 80, updatable = false)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "provider_status", length = 40)
    private String providerStatus;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "charge_date")
    private Instant chargeDate;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    PaymentTransaction(TenantSubscription subscription, String externalId, Instant now) {
        super(Ids.newId());
        this.tenantId = subscription.getTenantId();
        this.subscriptionId = subscription.getId();
        this.provider = subscription.getProvider();
        this.externalId = externalId;
        this.status = Status.PENDING;
        this.amount = subscription.getPrice();
        this.currency = subscription.getCurrency();
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(Status status, String providerStatus, BigDecimal amount, Instant chargeDate, Instant paidAt, Instant now) {
        this.status = status;
        this.providerStatus = providerStatus;
        if (amount != null) {
            this.amount = amount;
        }
        this.chargeDate = chargeDate;
        if (paidAt != null) {
            this.paidAt = paidAt;
        }
        this.updatedAt = now;
    }
}
