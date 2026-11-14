package com.climind.saas.calculators;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Cotizador de un negocio. {@code draftConfig} es el borrador editable; lo que ven los clientes es siempre una
 * {@link CalculatorVersion} publicada (inmutable).
 */
@Entity
@Table(name = "calculators")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Calculator extends AssignedIdEntity {

    public enum Status { BORRADOR, PUBLICADO, PAUSADO, ARCHIVADO }

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Setter
    @Column(nullable = false, length = 120)
    private String name;

    @Setter
    @Column(length = 500)
    private String description;

    @Setter
    @Column(nullable = false, length = 60)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.BORRADOR;

    @Column(nullable = false, length = 3)
    private String currency = "ARS";

    @Column(name = "template_code", length = 40, updatable = false)
    private String templateCode;

    /** Configuración en JSON (pricing.CalculatorConfig). Se valida antes de asignarse. */
    @Setter
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_config", nullable = false, columnDefinition = "jsonb")
    private String draftConfig;

    @Setter
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String appearance;

    @Column(name = "published_version_id")
    private UUID publishedVersionId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Bloqueo optimista: dos pestañas editando el mismo cotizador no se pisan en silencio. */
    @Version
    @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Calculator(UUID tenantId, String name, String slug, String templateCode, String draftConfig,
                      String appearance) {
        super(Ids.newId());
        this.tenantId = tenantId;
        this.name = name;
        this.slug = slug;
        this.templateCode = templateCode;
        this.draftConfig = draftConfig;
        this.appearance = appearance;
    }

    public boolean isArchived() {
        return status == Status.ARCHIVADO;
    }

    void published(UUID versionId, Instant at) {
        this.publishedVersionId = versionId;
        this.publishedAt = at;
        this.status = Status.PUBLICADO;
    }

    void pause() {
        this.status = Status.PAUSADO;
    }

    void resume() {
        this.status = Status.PUBLICADO;
    }

    void archive(Instant at) {
        this.status = Status.ARCHIVADO;
        this.archivedAt = at;
    }

    /** Al restaurar vuelve pausado si ya tenía una versión publicada (no se re-publica solo). */
    void restore() {
        this.status = publishedVersionId != null ? Status.PAUSADO : Status.BORRADOR;
        this.archivedAt = null;
    }
}
