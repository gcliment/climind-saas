package com.climind.saas.calculators;

import com.climind.saas.shared.persistence.AssignedIdEntity;
import com.climind.saas.shared.util.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Versión publicada e INMUTABLE de un cotizador (también protegida por un trigger en la base).
 * Las cotizaciones históricas la referencian: cambiar precios crea una versión nueva, nunca edita esta.
 */
@Entity
@Immutable
@Table(name = "calculator_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalculatorVersion extends AssignedIdEntity {

    @Column(name = "calculator_id", nullable = false, updatable = false)
    private UUID calculatorId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(nullable = false, length = 120, updatable = false)
    private String name;

    @Column(length = 500, updatable = false)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private String config;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private String appearance;

    @Column(name = "config_hash", nullable = false, length = 64, updatable = false)
    private String configHash;

    @Column(name = "published_by", updatable = false)
    private UUID publishedBy;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    CalculatorVersion(Calculator calculator, int versionNo, String configHash, UUID publishedBy, Instant publishedAt) {
        super(Ids.newId());
        this.calculatorId = calculator.getId();
        this.tenantId = calculator.getTenantId();
        this.versionNo = versionNo;
        this.name = calculator.getName();
        this.description = calculator.getDescription();
        this.config = calculator.getDraftConfig();
        this.appearance = calculator.getAppearance();
        this.configHash = configHash;
        this.publishedBy = publishedBy;
        this.publishedAt = publishedAt;
    }
}
