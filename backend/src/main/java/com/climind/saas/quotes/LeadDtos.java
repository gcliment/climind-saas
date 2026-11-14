package com.climind.saas.quotes;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** DTOs de la gestión de consultas. Nunca aceptan el negocio: sale de la sesión. */
public final class LeadDtos {

    private LeadDtos() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LeadSummary(
            UUID id,
            String code,
            Instant createdAt,
            String customerName,
            String customerWhatsapp,
            String customerEmail,
            UUID calculatorId,
            String calculatorName,
            String summary,
            BigDecimal total,
            String currency,
            String status,
            String statusLabel,
            boolean anonymized) {
    }

    public record HistoryItem(String kind, String fromStatus, String toStatus, String note, boolean byBusiness,
                              Instant createdAt) {
    }

    /**
     * @param result      desglose guardado al cotizar (no se recalcula)
     * @param whatsappUrl enlace para escribirle al cliente con un mensaje sugerido
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LeadDetail(
            LeadSummary lead,
            String comments,
            Instant consentAt,
            Instant statusChangedAt,
            JsonNode result,
            int priceScale,
            LocalDate validUntil,
            String disclaimer,
            Instant whatsappClickedAt,
            String whatsappUrl,
            List<HistoryItem> history) {
    }

    public record StatusOption(String status, String label, long count) {
    }

    public record ChangeStatusRequest(
            @NotNull(message = "Elegí un estado.")
            String status,

            @Size(max = 1000, message = "La nota admite hasta 1000 caracteres.")
            String note) {
    }

    public record NoteRequest(
            @NotBlank(message = "Escribí la nota.")
            @Size(max = 1000, message = "La nota admite hasta 1000 caracteres.")
            String note) {
    }

    /** Filtros del listado (todos opcionales). Fechas en la zona horaria del negocio. */
    public record LeadFilter(String status, String q, UUID calculatorId, LocalDate from, LocalDate to, String sort) {
    }
}
