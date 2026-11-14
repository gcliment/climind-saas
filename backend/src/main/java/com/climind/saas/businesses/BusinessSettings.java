package com.climind.saas.businesses;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Preferencias del negocio (notificaciones y retención de datos). */
@Entity
@Table(name = "business_settings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusinessSettings {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID tenantId;

    @Column(name = "notify_new_lead_email", nullable = false)
    private boolean notifyNewLeadEmail = true;

    @Column(name = "notify_usage_email", nullable = false)
    private boolean notifyUsageEmail = true;

    @Column(name = "lead_retention_months", nullable = false)
    private int leadRetentionMonths = 24;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public BusinessSettings(UUID tenantId) {
        this.tenantId = tenantId;
    }
}
