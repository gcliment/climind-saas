package com.climind.saas.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** DTOs de autenticación. Las contraseñas nunca se devuelven ni se imprimen ({@code toString} redactado). */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "Ingresá tu nombre.")
            @Size(max = 80, message = "El nombre admite hasta 80 caracteres.")
            String firstName,

            @NotBlank(message = "Ingresá tu apellido.")
            @Size(max = 80, message = "El apellido admite hasta 80 caracteres.")
            String lastName,

            @NotBlank(message = "Ingresá tu email.")
            @Email(message = "Ingresá un email válido.")
            @Size(max = 254, message = "El email es demasiado largo.")
            String email,

            @NotBlank(message = "Elegí una contraseña.")
            @Size(max = 200, message = "La contraseña es demasiado larga.")
            String password,

            @NotBlank(message = "Repetí la contraseña.")
            @Size(max = 200, message = "La contraseña es demasiado larga.")
            String passwordConfirmation,

            @AssertTrue(message = "Para crear la cuenta tenés que aceptar los términos y la política de privacidad.")
            boolean acceptTerms) {

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + "]";
        }
    }

    public record LoginRequest(
            @NotBlank(message = "Ingresá tu email.")
            @Size(max = 254, message = "El email es demasiado largo.")
            String email,

            @NotBlank(message = "Ingresá tu contraseña.")
            @Size(max = 200, message = "La contraseña es demasiado larga.")
            String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + "]";
        }
    }

    public record EmailRequest(
            @NotBlank(message = "Ingresá tu email.")
            @Email(message = "Ingresá un email válido.")
            @Size(max = 254, message = "El email es demasiado largo.")
            String email) {
    }

    public record TokenRequest(
            @NotBlank(message = "Falta el código del enlace.")
            @Size(max = 100, message = "El enlace no es válido.")
            String token) {

        @Override
        public String toString() {
            return "TokenRequest[***]";
        }
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "Falta el código del enlace.")
            @Size(max = 100, message = "El enlace no es válido.")
            String token,

            @NotBlank(message = "Elegí una contraseña.")
            @Size(max = 200, message = "La contraseña es demasiado larga.")
            String password,

            @NotBlank(message = "Repetí la contraseña.")
            @Size(max = 200, message = "La contraseña es demasiado larga.")
            String passwordConfirmation) {

        @Override
        public String toString() {
            return "ResetPasswordRequest[***]";
        }
    }

    public record MessageResponse(String message) {
    }

    public record UserView(UUID id, String email, String firstName, String lastName, String platformRole) {
    }

    /** @param deletionScheduledFor baja de la cuenta programada (null si no hay una en curso) */
    public record TenantView(UUID id, String name, String slug, String role, String status, boolean onboardingCompleted,
                             java.time.Instant deletionScheduledFor) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MeResponse(UserView user, TenantView tenant) {
    }
}
