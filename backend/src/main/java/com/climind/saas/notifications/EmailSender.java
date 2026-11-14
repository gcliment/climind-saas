package com.climind.saas.notifications;

/** Transporte de emails (SMTP, proveedor transaccional, o registro en memoria en desarrollo). */
public interface EmailSender {

    /** Envía el email o lanza una excepción si el transporte falla. */
    void send(EmailMessage message);
}
