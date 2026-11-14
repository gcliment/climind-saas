package com.climind.saas.notifications;

/** Email listo para enviar. {@code template} solo se usa para registro/diagnóstico. */
public record EmailMessage(String template, String to, String subject, String html, String text) {

    @Override
    public String toString() {
        // Nunca imprimir el cuerpo: puede contener tokens de verificación o recuperación.
        return "EmailMessage[template=" + template + "]";
    }
}
