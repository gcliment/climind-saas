package com.climind.saas.subscriptions.payments;

/** Falla de comunicación o configuración con el proveedor de pagos (nunca incluye credenciales). */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String message) {
        super(message);
    }
}
