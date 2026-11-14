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
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Plan comercial. Los valores se editan desde el panel de administración; los contratos vigentes guardan su
 * propia copia (ver {@link TenantSubscription}), así que editar un plan solo afecta contrataciones nuevas
 * (y al plan gratuito, que no es un contrato).
 */
@Entity
@Table(name = "subscription_plans")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionPlan extends AssignedIdEntity {

    public enum BillingPeriod { MONTHLY, YEARLY }

    @Setter(AccessLevel.NONE)
    @Column(nullable = false, length = 30, updatable = false)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 300)
    private String description;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency = "ARS";

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, length = 10)
    private BillingPeriod billingPeriod = BillingPeriod.MONTHLY;

    @Setter(AccessLevel.NONE)
    @Column(name = "is_free", nullable = false, updatable = false)
    private boolean free;

    @Column(name = "max_published_calculators", nullable = false)
    private int maxPublishedCalculators;

    @Column(name = "max_monthly_quotes", nullable = false)
    private int maxMonthlyQuotes;

    @Column(name = "csv_export", nullable = false)
    private boolean csvExport;

    @Column(name = "custom_branding", nullable = false)
    private boolean customBranding;

    @Column(name = "lead_email_notifications", nullable = false)
    private boolean leadEmailNotifications;

    @Column(name = "active_for_new", nullable = false)
    private boolean activeForNew;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @Setter(AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    SubscriptionPlan(String code, String name, BigDecimal price, Instant now) {
        super(Ids.newId());
        this.code = code;
        this.name = name;
        this.price = price;
        this.free = false;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
