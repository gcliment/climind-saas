package com.climind.saas.subscriptions.payments;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulador de Mercado Pago para desarrollo y tests (sin credenciales). Se comporta como el proveedor real:
 * crea suscripciones "pending" con un link de pago, registra cuotas aprobadas o rechazadas y firma sus
 * notificaciones con el mismo esquema HMAC.
 * <p>
 * <b>Nunca existe en producción:</b> el bean solo se crea con los perfiles dev o test, y la app no arranca en
 * prod con {@code PAYMENT_PROVIDER=fake} (ver {@code PaymentsConfig}).
 */
public class FakePaymentProvider implements PaymentProvider {

    /** Clave de firma del simulador (equivale a la clave secreta de Webhooks de Mercado Pago). */
    public static final String WEBHOOK_SECRET = "fake-webhook-secret";

    private final String checkoutBaseUrl;
    private final Clock clock;
    private final Map<String, Sub> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, RemotePayment> payments = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    private static final class Sub {
        final String id;
        final String reference;
        final BigDecimal amount;
        final String currency;
        volatile String status = "pending";

        Sub(String id, String reference, BigDecimal amount, String currency) {
            this.id = id;
            this.reference = reference;
            this.amount = amount;
            this.currency = currency;
        }
    }

    public FakePaymentProvider(String publicUrl, Clock clock) {
        this.checkoutBaseUrl = publicUrl + "/app/plan/simulador?sub=";
        this.clock = clock;
    }

    @Override
    public String name() {
        return "FAKE";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public Checkout createSubscription(CheckoutRequest request) {
        String id = "fake-sub-" + UUID.randomUUID().toString().substring(0, 8);
        subscriptions.put(id, new Sub(id, request.reference(), request.amount(), request.currency()));
        return new Checkout(id, checkoutBaseUrl + id, "pending");
    }

    @Override
    public Optional<RemoteSubscription> fetchSubscription(String externalId) {
        return Optional.ofNullable(subscriptions.get(externalId))
                .map(s -> new RemoteSubscription(s.id, s.reference, s.status, s.amount, null));
    }

    @Override
    public Optional<RemotePayment> fetchPayment(String externalId) {
        return Optional.ofNullable(payments.get(externalId));
    }

    @Override
    public List<RemotePayment> searchPayments(String subscriptionExternalId) {
        List<RemotePayment> result = new ArrayList<>();
        payments.values().stream().filter(p -> subscriptionExternalId.equals(p.subscriptionExternalId()))
                .forEach(result::add);
        return result;
    }

    @Override
    public void cancelSubscription(String externalId) {
        Sub sub = subscriptions.get(externalId);
        if (sub != null) {
            sub.status = "cancelled";
        }
    }

    @Override
    public boolean verifySignature(WebhookRequest request) {
        return MercadoPagoSignature.verify(request.signature(), request.requestId(), request.dataId(), WEBHOOK_SECRET,
                clock.instant(), Duration.ofMinutes(15));
    }

    // ------------------------------------------------------------------ operaciones del simulador

    public boolean exists(String subscriptionId) {
        return subscriptions.containsKey(subscriptionId);
    }

    /**
     * Simula el cobro de una cuota: aprobada (la suscripción queda "authorized") o rechazada (cuota en
     * "recycling", como hace Mercado Pago mientras reintenta).
     */
    public RemotePayment charge(String subscriptionId, boolean approved) {
        return charge(subscriptionId, approved ? "approved" : "rejected");
    }

    /** @param paymentStatus approved | rejected | in_process (como los informa Mercado Pago) */
    public RemotePayment charge(String subscriptionId, String paymentStatus) {
        Sub sub = subscriptions.get(subscriptionId);
        if (sub == null) {
            throw new IllegalArgumentException("Suscripción simulada inexistente");
        }
        boolean approved = "approved".equals(paymentStatus);
        if (approved && "pending".equals(sub.status)) {
            sub.status = "authorized";
        }
        Instant now = clock.instant();
        String id = "fake-pay-" + sequence.incrementAndGet();
        String chargeStatus = approved ? "processed" : "rejected".equals(paymentStatus) ? "recycling" : "waiting for gateway";
        RemotePayment payment = new RemotePayment(id, sub.id, chargeStatus, paymentStatus, sub.amount, sub.currency,
                now, approved ? now : null);
        payments.put(id, payment);
        return payment;
    }

    public void setStatus(String subscriptionId, String status) {
        Sub sub = subscriptions.get(subscriptionId);
        if (sub == null) {
            throw new IllegalArgumentException("Suscripción simulada inexistente");
        }
        sub.status = status;
    }

    /** Firma una notificación como lo haría el proveedor (para el simulador y los tests). */
    public String sign(String dataId, String requestId, Instant at) {
        String ts = String.valueOf(at.getEpochSecond());
        return "ts=" + ts + ",v1=" + MercadoPagoSignature.hmac(WEBHOOK_SECRET,
                MercadoPagoSignature.manifest(dataId, requestId, ts));
    }

    public void reset() {
        subscriptions.clear();
        payments.clear();
    }
}
