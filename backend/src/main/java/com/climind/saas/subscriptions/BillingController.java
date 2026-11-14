package com.climind.saas.subscriptions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Planes y suscripción")
@RestController
public class BillingController {

    private final BillingService billing;
    private final WebhookService webhooks;

    public BillingController(BillingService billing, WebhookService webhooks) {
        this.billing = billing;
        this.webhooks = webhooks;
    }

    @Operation(summary = "Planes disponibles (público: página de precios)")
    @Tag(name = "Catálogo")
    @GetMapping("/api/v1/catalog/plans")
    public List<BillingDtos.PlanView> plans() {
        return billing.catalog();
    }

    @Operation(summary = "Plan vigente, consumo del período, suscripción y pagos del negocio")
    @GetMapping("/api/v1/billing")
    public BillingDtos.BillingOverview overview() {
        return billing.overview();
    }

    @Operation(summary = "Iniciar la contratación de un plan pago (devuelve el link de pago del proveedor)")
    @PostMapping("/api/v1/billing/checkout")
    public BillingDtos.CheckoutResponse checkout(@Valid @RequestBody BillingDtos.CheckoutRequest request) {
        return billing.checkout(request);
    }

    @Operation(summary = "Descartar una contratación iniciada y no pagada")
    @PostMapping("/api/v1/billing/checkout/cancel")
    public BillingDtos.BillingOverview cancelCheckout() {
        return billing.cancelCheckout();
    }

    @Operation(summary = "Cancelar el plan pago (conserva los beneficios hasta el fin del período pagado)")
    @PostMapping("/api/v1/billing/cancel")
    public BillingDtos.BillingOverview cancel() {
        return billing.cancel();
    }

    @Operation(summary = "Notificaciones de Mercado Pago (firma x-signature obligatoria)")
    @Tag(name = "Webhooks")
    @PostMapping("/api/v1/webhooks/mercadopago")
    public Map<String, String> mercadoPago(@RequestHeader(value = "x-signature", required = false) String signature,
                                           @RequestHeader(value = "x-request-id", required = false) String requestId,
                                           @RequestBody(required = false) String body, HttpServletRequest request) {
        Map<String, String> query = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> query.put(k, v.length == 0 ? null : v[0]));
        return Map.of("result", webhooks.receive(signature, requestId, query, body));
    }
}
