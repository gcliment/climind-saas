package com.climind.saas.notifications;

/** Plantillas de email transaccional (archivos en {@code templates/email/<file>.html}). */
public enum MailTemplate {
    VERIFY_EMAIL("verify-email", "Confirmá tu email para empezar con Climind Cotiza"),
    ACCOUNT_EXISTS("account-exists", "Ya tenés una cuenta en Climind Cotiza"),
    PASSWORD_RESET("password-reset", "Restablecé tu contraseña de Climind Cotiza"),
    PASSWORD_CHANGED("password-changed", "Tu contraseña de Climind Cotiza fue cambiada"),
    WELCOME("welcome", "Te damos la bienvenida a Climind Cotiza"),
    NEW_LEAD("new-lead", "Recibiste una consulta nueva en Climind Cotiza"),
    USAGE_WARNING("billing-notice", "Tu plan de Climind Cotiza está cerca del límite"),
    USAGE_LIMIT_REACHED("billing-notice", "Alcanzaste el límite de cotizaciones de tu plan"),
    SUBSCRIPTION_ACTIVATED("billing-notice", "Tu plan de Climind Cotiza está activo"),
    PAYMENT_FAILED("billing-notice", "No pudimos cobrar tu plan de Climind Cotiza"),
    SUBSCRIPTION_CANCELED("billing-notice", "Cancelaste tu plan de Climind Cotiza"),
    SUBSCRIPTION_EXPIRED("billing-notice", "Tu plan de Climind Cotiza venció");

    private final String file;
    private final String subject;

    MailTemplate(String file, String subject) {
        this.file = file;
        this.subject = subject;
    }

    public String file() {
        return file;
    }

    public String subject() {
        return subject;
    }
}
