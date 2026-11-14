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

import java.time.Instant;
import java.util.UUID;

/** Evento de la línea de tiempo de una consulta (tabla {@code quote_status_history}). */
@Entity
@Table(name = "quote_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuoteHistoryEntry extends AssignedIdEntity {

    public enum Kind { CREATED, STATUS_CHANGED, NOTE, ANONYMIZED }

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private QuoteRequest.Status fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 20, updatable = false)
    private QuoteRequest.Status toStatus;

    /** Nota del negocio. Se borra al anonimizar la consulta (puede tener datos personales). */
    @Column(length = 1000)
    private String note;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    QuoteHistoryEntry(QuoteRequest request, Kind kind, QuoteRequest.Status from, QuoteRequest.Status to, String note,
                      UUID actorUserId, Instant createdAt) {
        super(Ids.newId());
        this.tenantId = request.getTenantId();
        this.requestId = request.getId();
        this.kind = kind;
        this.fromStatus = from;
        this.toStatus = to;
        this.note = note;
        this.actorUserId = actorUserId;
        this.createdAt = createdAt;
    }

    void clearNote() {
        this.note = null;
    }
}
