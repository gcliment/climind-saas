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
    SUBSCRIPTION_EXPIRED("billing-notice", "Tu plan de Climind Cotiza venció"),
    ACCOUNT_DELETION_SCHEDULED("account-deletion", "Programaste la baja de tu cuenta de Climind Cotiza"),
    /** Confirmación de baja: el registro del envío no guarda la dirección (la cuenta ya no existe). */
    ACCOUNT_DELETED("account-deleted", "Tu cuenta de Climind Cotiza fue eliminada", false),
    // Emails de producto: llevan enlace de baja y se envían solo a quien los acepta (users.product_emails).
    ACTIVATION_REMINDER("activation-reminder", "Tu primer cotizador está a unos minutos"),
    MONTHLY_SUMMARY("monthly-summary", "Tu resumen del mes en Climind Cotiza");

    private final String file;
    private final String subject;
    private final boolean logRecipient;

    MailTemplate(String file, String subject) {
        this(file, subject, true);
    }

    MailTemplate(String file, String subject, boolean logRecipient) {
        this.file = file;
        this.subject = subject;
        this.logRecipient = logRecipient;
    }

    /** Si el registro de entregas guarda la dirección del destinatario. */
    public boolean logRecipient() {
        return logRecipient;
    }

    public String file() {
        return file;
    }

    public String subject() {
        return subject;
    }
}
