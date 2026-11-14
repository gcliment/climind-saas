package com.climind.saas.quotes;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * DTOs del cotizador público. Exponen solo lo necesario para mostrar el formulario y el resultado: nunca las
 * reglas de precio, ni ids internos, ni datos de otros clientes.
 */
public final class PublicQuoteDtos {

    private PublicQuoteDtos() {
    }

    /** Datos de contacto que el negocio publicó. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BusinessCard(String name, String description, String logoUrl, String whatsapp, String phone,
                               String email, String website) {
    }

    /**
     * Cotizador público. Si está pausado, {@code available=false} y no incluye el formulario.
     *
     * @param questions preguntas (sin precios) para armar el formulario y decidir cuáles se ven
     * @param priceScale 0 = pesos enteros, 2 = con centavos
     * @param leadCapture AFTER_ESTIMATE (los datos se piden después de mostrar el precio) o BEFORE_ESTIMATE
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublicCalculator(
            boolean available,
            String name,
            String description,
            String accentColor,
            BusinessCard business,
            JsonNode questions,
            String currency,
            Integer priceScale,
            String disclaimer,
            Integer validityDays,
            String leadCapture,
            /** Mostrar "Creado con Climind Cotiza" (planes sin personalización visual). */
            boolean branding) {
    }

    /** Datos que deja el cliente. El consentimiento es obligatorio. */
    public record ContactRequest(
            @NotBlank(message = "Ingresá tu nombre.")
            @Size(min = 2, max = 120, message = "El nombre debe tener entre 2 y 120 caracteres.")
            String name,

            @NotBlank(message = "Ingresá tu WhatsApp.")
            @Size(max = 30, message = "Ingresá un número válido.")
            String whatsapp,

            @Email(message = "Ingresá un email válido.")
            @Size(max = 254, message = "El email es demasiado largo.")
            String email,

            @Size(max = 1000, message = "Los comentarios admiten hasta 1000 caracteres.")
            String comments,

            @AssertTrue(message = "Para enviar la consulta tenés que aceptar que el negocio use estos datos para responderte.")
            boolean consent,

            /** Campo trampa para bots: las personas no lo ven ni lo completan. */
            @Size(max = 200)
            String website) {
    }

    public record CreateQuoteRequest(
            @Size(max = 60, message = "Demasiadas respuestas.")
            Map<String, Object> answers,

            /** Obligatorio si el cotizador pide los datos antes de mostrar el precio. */
            @Valid
            ContactRequest contact) {
    }

    /** Estado de la consulta asociada a la cotización (sin repetir los datos personales). */
    public record RequestStatus(boolean sent, Instant sentAt) {
    }

    /**
     * Cotización emitida.
     *
     * @param token      enlace privado de la cotización; solo viaja al crearla (el visitante lo guarda)
     * @param result     desglose calculado por el servidor (snapshot)
     * @param whatsappUrl enlace oficial wa.me con el mensaje armado, si el negocio cargó su WhatsApp
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuoteView(
            String code,
            String token,
            Instant createdAt,
            LocalDate validUntil,
            boolean expired,
            String calculatorName,
            String calculatorUrl,
            BusinessCard business,
            String accentColor,
            JsonNode result,
            Integer priceScale,
            String currency,
            String disclaimer,
            String whatsappUrl,
            String leadCapture,
            RequestStatus request,
            boolean branding) {
    }
}
