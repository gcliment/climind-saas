package com.climind.saas.calculators;

import com.climind.saas.pricing.ConfigIssue;
import com.climind.saas.pricing.PriceBreakdown;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** DTOs del módulo de cotizadores. Ninguno incluye ni acepta el negocio (tenant): sale de la sesión. */
public final class CalculatorDtos {

    private CalculatorDtos() {
    }

    public record CalculatorSummary(
            UUID id,
            String name,
            String slug,
            String status,
            String templateCode,
            Instant updatedAt,
            Integer publishedVersionNo,
            Instant publishedAt,
            boolean hasUnpublishedChanges,
            String publicUrl) {
    }

    /**
     * @param publishIssues lo que falta para poder publicar el borrador actual (vacío = publicable)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CalculatorDetail(
            UUID id,
            String name,
            String description,
            String slug,
            String status,
            String templateCode,
            int lockVersion,
            JsonNode config,
            Appearance appearance,
            Integer publishedVersionNo,
            Instant publishedAt,
            boolean hasUnpublishedChanges,
            String publicUrl,
            List<ConfigIssue> publishIssues,
            Instant updatedAt) {
    }

    public record CreateCalculatorRequest(
            @NotBlank(message = "Poné un nombre al cotizador.")
            @Size(max = 120, message = "El nombre admite hasta 120 caracteres.")
            String name,

            @Size(max = 40)
            String templateCode) {
    }

    public record UpdateCalculatorRequest(
            @NotBlank(message = "Poné un nombre al cotizador.")
            @Size(max = 120, message = "El nombre admite hasta 120 caracteres.")
            String name,

            @Size(max = 500, message = "La descripción admite hasta 500 caracteres.")
            String description,

            @NotBlank(message = "Elegí la dirección del cotizador.")
            @Size(max = 60, message = "La dirección admite hasta 60 caracteres.")
            String slug,

            Appearance appearance,

            @NotNull(message = "Falta la configuración del cotizador.")
            JsonNode config,

            /** Versión que el cliente editó (bloqueo optimista). */
            @NotNull
            Integer lockVersion) {
    }

    public record PreviewRequest(
            @Size(max = 60, message = "Demasiadas respuestas.")
            Map<String, Object> answers,
            /** Configuración sin guardar (vista previa en vivo del constructor). Si falta, usa el borrador. */
            JsonNode config) {
    }

    /** Si la configuración tiene problemas estructurales no hay resultado: se devuelven los problemas. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PreviewResponse(PriceBreakdown result, List<ConfigIssue> configIssues) {
    }

    public record PublishResponse(CalculatorDetail calculator, boolean newVersion, int versionNo) {
    }

    public record VersionSummary(int versionNo, Instant publishedAt, boolean current) {
    }

    public record TemplateSummary(String code, String name, String industry, String industryLabel, String description,
                                  List<String> objectives, int questionCount, String calculatorName) {
    }

    public record TemplateDetail(String code, String name, String description, String calculatorName,
                                 String calculatorDescription, JsonNode config) {
    }
}
