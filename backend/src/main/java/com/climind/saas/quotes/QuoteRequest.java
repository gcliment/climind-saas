package com.climind.saas.quotes;

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

/**
 * Consulta: el cliente dejó sus datos para que el negocio lo contacte por una cotización. Es la única entidad
 * con datos personales de terceros; se puede anonimizar (a pedido o por la política de retención del negocio).
 */
@Entity
@Table(name = "quote_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuoteRequest extends AssignedIdEntity {

    /** Estados del seguimiento. CONVERTIDA = venta confirmada por el negocio (nunca automática). */
    public enum Status {
        NUEVA("Nueva"),
        CONTACTADA("Contactada"),
        EN_NEGOCIACION("En negociación"),
        CONVERTIDA("Convertida"),
        PERDIDA("Perdida");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "simulation_id", nullable = false, updatable = false)
    private UUID simulationId;

    @Column(name = "calculator_id", nullable = false, updatable = false)
    private UUID calculatorId;

    @Column(nullable = false, length = 12, updatable = false)
    private String code;

    @Column(name = "calculator_name", nullable = false, length = 120, updatable = false)
    private String calculatorName;

    @Column(nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal total;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(length = 300)
    private String summary;

    @Column(name = "customer_name", length = 120)
    private String customerName;

    @Column(name = "customer_whatsapp", length = 15)
    private String customerWhatsapp;

    @Column(name = "customer_email", length = 254)
    private String customerEmail;

    @Column(length = 1000)
    private String comments;

    @Column(name = "consent_at", nullable = false, updatable = false)
    private Instant consentAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.NUEVA;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    QuoteRequest(QuoteSimulation simulation, String summary, String customerName, String customerWhatsapp,
                 String customerEmail, String comments, Instant now) {
        super(Ids.newId());
        this.tenantId = simulation.getTenantId();
        this.simulationId = simulation.getId();
        this.calculatorId = simulation.getCalculatorId();
        this.code = simulation.getCode();
        this.calculatorName = simulation.getCalculatorName();
        this.total = simulation.getTotal();
        this.currency = simulation.getCurrency();
        this.summary = summary;
        this.customerName = customerName;
        this.customerWhatsapp = customerWhatsapp;
        this.customerEmail = customerEmail;
        this.comments = comments;
        this.consentAt = now;
        this.statusChangedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isAnonymized() {
        return anonymizedAt != null;
    }

    void changeStatus(Status next, Instant now) {
        this.status = next;
        this.statusChangedAt = now;
        this.updatedAt = now;
    }

    void touch(Instant now) {
        this.updatedAt = now;
    }

    /** Borra los datos personales; quedan el código, el importe y el estado (para las estadísticas). */
    void anonymize(Instant now) {
        this.customerName = null;
        this.customerWhatsapp = null;
        this.customerEmail = null;
        this.comments = null;
        this.summary = null;
        this.anonymizedAt = now;
        this.updatedAt = now;
    }
}
