package com.climind.saas.subscriptions.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Proveedor de cobros recurrentes. Implementaciones: Mercado Pago (real) y un simulador que SOLO existe en los
 * perfiles dev y test. Todo estado que habilite beneficios se obtiene consultando al proveedor servidor a
 * servidor ({@link #fetchSubscription}, {@link #fetchPayment}); nunca del contenido de una notificación ni de
 * la URL de regreso del checkout.
 */
public interface PaymentProvider {

    /** MERCADOPAGO | FAKE. */
    String name();

    /** Si tiene credenciales para operar (sin credenciales, la contratación responde "no disponible"). */
    boolean configured();

    /** Crea la suscripción en el proveedor y devuelve dónde completar el pago. */
    Checkout createSubscription(CheckoutRequest request);

    Optional<RemoteSubscription> fetchSubscription(String externalId);

    Optional<RemotePayment> fetchPayment(String externalId);

    /** Cobros de una suscripción (para la conciliación). */
    List<RemotePayment> searchPayments(String subscriptionExternalId);

    void cancelSubscription(String externalId);

    /** Verifica la firma de una notificación. */
    boolean verifySignature(WebhookRequest request);

    record CheckoutRequest(String reference, String reason, BigDecimal amount, String currency, String payerEmail,
                           String backUrl) {
    }

    record Checkout(String externalId, String checkoutUrl, String status) {
    }

    /**
     * @param status estado crudo del proveedor (p. ej. pending, authorized, paused, cancelled)
     */
    record RemoteSubscription(String externalId, String reference, String status, BigDecimal amount,
                              Instant nextChargeDate) {
    }

    /**
     * Cobro de una cuota.
     *
     * @param chargeStatus  estado de la cuota (scheduled, processed, recycling, cancelled…)
     * @param paymentStatus estado del pago (approved, rejected, pending, in_process, refunded…), null si no se intentó
     */
    record RemotePayment(String externalId, String subscriptionExternalId, String chargeStatus, String paymentStatus,
                         BigDecimal amount, String currency, Instant chargeDate, Instant paidAt) {
    }

    /** Lo necesario para validar y procesar una notificación entrante. */
    record WebhookRequest(String signature, String requestId, String dataId, String type, String notificationId,
                          String body) {
    }
}
