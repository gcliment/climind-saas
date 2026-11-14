package com.climind.saas.notifications;

/**
 * Email listo para enviar. {@code template} solo se usa para registro/diagnóstico.
 *
 * @param unsubscribeUrl solo en emails de producto: URL de baja en un clic (encabezado List-Unsubscribe)
 */
public record EmailMessage(String template, String to, String subject, String html, String text,
                           String unsubscribeUrl) {

    public EmailMessage(String template, String to, String subject, String html, String text) {
        this(template, to, subject, html, text, null);
    }

    @Override
    public String toString() {
        // Nunca imprimir el cuerpo: puede contener tokens de verificación o recuperación.
        return "EmailMessage[template=" + template + "]";
    }
}
