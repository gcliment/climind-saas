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

    public enum Status { ACTIVE, SUSPENDED, DELETED }

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

    /** Negocio ficticio de las demos públicas (sin consultas, sin cupo, fuera de las métricas). */
    @Column(nullable = false, updatable = false)
    private boolean demo;

    @Column(name = "deletion_requested_at")
    private Instant deletionRequestedAt;

    /** Baja pedida por el dueño: desde esta fecha se eliminan los datos. Hasta entonces se puede cancelar. */
    @Column(name = "deletion_scheduled_for")
    private Instant deletionScheduledFor;

    @Column(name = "deletion_requested_by")
    private java.util.UUID deletionRequestedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

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

    /** Negocio de demostración (lo crea solo el seeder de las demos públicas). */
    public static Tenant demo(String name, String slug) {
        Tenant tenant = new Tenant(name, slug);
        tenant.demo = true;
        return tenant;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /** Visible para el público: activo y sin una baja en curso (los cotizadores dejan de atender al pedirla). */
    public boolean isPubliclyVisible() {
        return status == Status.ACTIVE && deletionScheduledFor == null;
    }

    public boolean isDeletionScheduled() {
        return deletionScheduledFor != null && deletedAt == null;
    }

    public void suspend(String reason, Instant at) {
        if (status == Status.DELETED) {
            return;
        }
        this.status = Status.SUSPENDED;
        this.suspendedReason = reason;
        this.suspendedAt = at;
    }

    public void reactivate() {
        if (status != Status.SUSPENDED) {
            return;
        }
        this.status = Status.ACTIVE;
        this.suspendedReason = null;
        this.suspendedAt = null;
    }

    public void scheduleDeletion(java.util.UUID requestedBy, Instant now, Instant scheduledFor) {
        this.deletionRequestedBy = requestedBy;
        this.deletionRequestedAt = now;
        this.deletionScheduledFor = scheduledFor;
    }

    public void cancelDeletion() {
        this.deletionRequestedBy = null;
        this.deletionRequestedAt = null;
        this.deletionScheduledFor = null;
    }

    /** Fin de la baja: el negocio queda como registro anonimizado (lo referencian pagos y auditoría). */
    public void markDeleted(String anonymousSlug, Instant at) {
        this.status = Status.DELETED;
        this.name = "Cuenta eliminada";
        this.slug = anonymousSlug;
        this.deletedAt = at;
        this.suspendedReason = null;
    }
}
