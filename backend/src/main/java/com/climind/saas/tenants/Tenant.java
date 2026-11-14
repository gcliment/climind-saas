package com.climind.saas.tenants;

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
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** Espacio de trabajo de un negocio. Todo recurso privado pertenece a exactamente un tenant. */
@Entity
@Table(name = "tenants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tenant extends AssignedIdEntity {

    public enum Status { ACTIVE, SUSPENDED }

    @Setter
    @Column(nullable = false, length = 120)
    private String name;

    @Setter
    @Column(nullable = false, length = 60, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(nullable = false, length = 2)
    private String country = "AR";

    @Column(nullable = false, length = 3)
    private String currency = "ARS";

    @Column(nullable = false, length = 64)
    private String timezone = "America/Argentina/Buenos_Aires";

    @Setter
    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspended_reason", length = 300)
    private String suspendedReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Tenant(String name, String slug) {
        super(Ids.newId());
        this.name = name;
        this.slug = slug;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public void suspend(String reason, Instant at) {
        this.status = Status.SUSPENDED;
        this.suspendedReason = reason;
        this.suspendedAt = at;
    }

    public void reactivate() {
        this.status = Status.ACTIVE;
        this.suspendedReason = null;
        this.suspendedAt = null;
    }
}
