package com.climind.saas.businesses;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** DTOs del módulo de negocios. */
public final class BusinessDtos {

    private BusinessDtos() {
    }

    public record BusinessProfileResponse(
            String businessName,
            String industry,
            String industryLabel,
            String description,
            String phone,
            String whatsapp,
            String contactEmail,
            String website,
            String publicSlug,
            String logoUrl,
            boolean onboardingCompleted) {
    }

    public record LogoResponse(String logoUrl) {
    }

    /**
     * Actualización del perfil. No incluye (ni acepta) ningún identificador de negocio: el negocio se
     * resuelve desde la sesión. Campos desconocidos en el JSON se ignoran.
     */
    public record UpdateBusinessProfileRequest(
            @NotBlank(message = "Ingresá el nombre de tu negocio.")
            @Size(min = 2, max = 120, message = "El nombre debe tener entre 2 y 120 caracteres.")
            String businessName,

            String industry,

            @Size(max = 500, message = "La descripción admite hasta 500 caracteres.")
            String description,

            @Pattern(regexp = "^$|^[0-9+()\\-\\s]{6,30}$", message = "Ingresá un teléfono válido.")
            String phone,

            @Pattern(regexp = "^$|^[0-9+()\\-\\s]{8,25}$",
                    message = "Ingresá el WhatsApp con código de país y área, por ejemplo +54 9 11 2345-6789.")
            String whatsapp,

            @Email(message = "Ingresá un email válido.")
            @Size(max = 254, message = "El email es demasiado largo.")
            String contactEmail,

            @Size(max = 200, message = "La dirección web es demasiado larga.")
            String website) {
    }

    public record IndustryOption(String code, String label) {
    }

    /** Preferencias del negocio. */
    public record BusinessSettingsResponse(boolean notifyNewLeadEmail, int leadRetentionMonths) {
    }

    public record UpdateBusinessSettingsRequest(
            @jakarta.validation.constraints.NotNull
            Boolean notifyNewLeadEmail,

            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Min(value = 1, message = "El plazo mínimo es de 1 mes.")
            @jakarta.validation.constraints.Max(value = 120, message = "El plazo máximo es de 120 meses.")
            Integer leadRetentionMonths) {
    }
}
