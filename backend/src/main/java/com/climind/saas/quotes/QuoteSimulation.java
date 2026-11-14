package com.climind.saas.quotes;

import com.climind.saas.shared.persistence.AssignedIdEntity;
import com.climind.saas.shared.util.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cotización calculada por un cliente con la versión publicada de un cotizador. Es un snapshot: guarda
 * respuestas, desglose, total, aclaración y vigencia tal como se mostraron. El importe no cambia nunca
 * (también lo protege un trigger en la base).
 */
@Entity
@Table(name = "quote_simulations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuoteSimulation extends AssignedIdEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "calculator_id", nullable = false, updatable = false)
    private UUID calculatorId;

    @Column(name = "calculator_version_id", nullable = false, updatable = false)
    private UUID calculatorVersionId;

    @Column(nullable = false, length = 12, updatable = false)
    private String code;

    @Column(name = "access_token", nullable = false, length = 43, updatable = false)
    private String accessToken;

    @Column(name = "calculator_name", nullable = false, length = 120, updatable = false)
    private String calculatorName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String answers;

    /** {@link com.climind.saas.pricing.PriceBreakdown} serializado. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String result;

    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal total;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "price_scale", nullable = false, updatable = false)
    private short priceScale;

    @Column(length = 500, updatable = false)
    private String disclaimer;

    @Column(name = "valid_until", updatable = false)
    private LocalDate validUntil;

    @Column(name = "answers_hash", nullable = false, length = 64, updatable = false)
    private String answersHash;

    @Column(name = "visitor_hash", length = 64, updatable = false)
    private String visitorHash;

    @Column(name = "whatsapp_clicked_at")
    private Instant whatsappClickedAt;

    @Column(name = "redacted_at")
    private Instant redactedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    QuoteSimulation(UUID tenantId, UUID calculatorId, UUID calculatorVersionId, String code, String accessToken,
                    String calculatorName, String answers, String result, BigDecimal total, String currency,
                    int priceScale, String disclaimer, LocalDate validUntil, String answersHash, String visitorHash,
                    Instant createdAt) {
        super(Ids.newId());
        this.tenantId = tenantId;
        this.calculatorId = calculatorId;
        this.calculatorVersionId = calculatorVersionId;
        this.code = code;
        this.accessToken = accessToken;
        this.calculatorName = calculatorName;
        this.answers = answers;
        this.result = result;
        this.total = total;
        this.currency = currency;
        this.priceScale = (short) priceScale;
        this.disclaimer = disclaimer;
        this.validUntil = validUntil;
        this.answersHash = answersHash;
        this.visitorHash = visitorHash;
        this.createdAt = createdAt;
    }

    void whatsappClicked(Instant at) {
        if (whatsappClickedAt == null) {
            whatsappClickedAt = at;
        }
    }

    /** Reemplaza las respuestas por versiones sin textos libres del cliente (el importe no cambia). */
    void redact(String answers, String result, Instant at) {
        this.answers = answers;
        this.result = result;
        this.redactedAt = at;
    }

    public boolean isExpired(LocalDate today) {
        return validUntil != null && today.isAfter(validUntil);
    }
}
