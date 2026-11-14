package com.climind.saas.subscriptions;

import com.climind.saas.shared.web.ApiException;
import com.climind.saas.subscriptions.payments.FakePaymentProvider;
import com.climind.saas.subscriptions.payments.PaymentProvider;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Simulador de Mercado Pago para desarrollo (perfil dev; /api/dev está denegado en cualquier otro perfil).
 * Cambia el estado de la suscripción simulada y envía la notificación FIRMADA por el mismo camino que una real
 * (validación de firma, deduplicación y sincronización consultando al "proveedor").
 */
@Hidden
@Profile("dev")
@RestController
public class DevPaymentsController {

    private final PaymentProvider provider;
    private final WebhookService webhooks;
    private final Clock clock;

    public DevPaymentsController(PaymentProvider provider, WebhookService webhooks, Clock clock) {
        this.provider = provider;
        this.webhooks = webhooks;
        this.clock = clock;
    }

    /** {@code result}: approved | rejected | in_process. */
    @PostMapping("/api/dev/payments/{subscription}/charge")
    public Map<String, String> charge(@PathVariable String subscription, @RequestParam String result) {
        FakePaymentProvider fake = fake(subscription);
        if (!java.util.Set.of("approved", "rejected", "in_process").contains(result)) {
            throw ApiException.badRequest("INVALID_RESULT", "Resultado inválido.");
        }
        PaymentProvider.RemotePayment payment = fake.charge(subscription, result);
        notify(fake, "subscription_authorized_payment", payment.externalId());
        return Map.of("payment", payment.externalId(), "status", payment.paymentStatus());
    }

    /** {@code value}: authorized | paused | cancelled. */
    @PostMapping("/api/dev/payments/{subscription}/status")
    public Map<String, String> status(@PathVariable String subscription, @RequestParam String value) {
        FakePaymentProvider fake = fake(subscription);
        fake.setStatus(subscription, value);
        notify(fake, "subscription_preapproval", subscription);
        return Map.of("status", value);
    }

    private void notify(FakePaymentProvider fake, String type, String dataId) {
        String requestId = UUID.randomUUID().toString();
        String body = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type + "\",\"action\":\"updated\","
                + "\"live_mode\":false,\"data\":{\"id\":\"" + dataId + "\"}}";
        webhooks.receive(fake.sign(dataId, requestId, clock.instant()), requestId,
                Map.of("type", type, "data.id", dataId), body);
    }

    private FakePaymentProvider fake(String subscription) {
        if (!(provider instanceof FakePaymentProvider fake) || !fake.exists(subscription)) {
            throw ApiException.notFound("No existe esa suscripción simulada.");
        }
        return fake;
    }
}
