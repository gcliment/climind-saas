package com.climind.saas.subscriptions.payments;

import com.climind.saas.shared.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Suscripciones de Mercado Pago sin plan asociado y con pago pendiente ({@code POST /preapproval} con
 * {@code status=pending}): el cliente elige el medio de pago en el checkout de Mercado Pago ({@code init_point}).
 * <ul>
 *   <li>{@code GET /preapproval/{id}}: estado de la suscripción (pending, authorized, paused, cancelled).</li>
 *   <li>{@code GET /authorized_payments/{id}}: cuota con su pago (approved, rejected…).</li>
 *   <li>{@code GET /authorized_payments/search?preapproval_id=}: cuotas de una suscripción (conciliación).</li>
 *   <li>{@code PUT /preapproval/{id}} con {@code status=cancelled}: baja.</li>
 * </ul>
 * El access token solo vive en el backend y nunca se registra en logs.
 */
@Slf4j
public class MercadoPagoProvider implements PaymentProvider {

    private final RestClient http;
    private final AppProperties.MercadoPago config;
    private final Clock clock;

    public MercadoPagoProvider(RestClient.Builder builder, AppProperties properties, Clock clock) {
        this.config = properties.getPayments().getMercadopago();
        this.clock = clock;
        this.http = builder.clone()
                .baseUrl(config.getApiUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new PaymentProviderException("Mercado Pago respondió " + res.getStatusCode().value());
                })
                .build();
    }

    @Override
    public String name() {
        return "MERCADOPAGO";
    }

    @Override
    public boolean configured() {
        return config.getAccessToken() != null && !config.getAccessToken().isBlank();
    }

    private String bearer() {
        if (!configured()) {
            throw new PaymentProviderException("Mercado Pago no está configurado (falta MERCADOPAGO_ACCESS_TOKEN)");
        }
        return "Bearer " + config.getAccessToken();
    }

    @Override
    public Checkout createSubscription(CheckoutRequest request) {
        Map<String, Object> autoRecurring = new LinkedHashMap<>();
        autoRecurring.put("frequency", 1);
        autoRecurring.put("frequency_type", "months");
        autoRecurring.put("transaction_amount", request.amount());
        autoRecurring.put("currency_id", request.currency());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", request.reason());
        body.put("external_reference", request.reference());
        body.put("payer_email", request.payerEmail());
        body.put("auto_recurring", autoRecurring);
        body.put("back_url", request.backUrl());
        body.put("status", "pending");
        JsonNode response = http.post().uri("/preapproval")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .header("X-Idempotency-Key", request.reference())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        String id = text(response, "id");
        String url = text(response, "init_point");
        if (id == null || url == null) {
            throw new PaymentProviderException("Mercado Pago no devolvió el id o el link de pago de la suscripción");
        }
        return new Checkout(id, url, text(response, "status"));
    }

    @Override
    public Optional<RemoteSubscription> fetchSubscription(String externalId) {
        return get("/preapproval/{id}", externalId).map(n -> new RemoteSubscription(text(n, "id"),
                text(n, "external_reference"), text(n, "status"),
                decimal(n.path("auto_recurring").path("transaction_amount")), instant(text(n, "next_payment_date"))));
    }

    @Override
    public Optional<RemotePayment> fetchPayment(String externalId) {
        return get("/authorized_payments/{id}", externalId).map(this::payment);
    }

    @Override
    public List<RemotePayment> searchPayments(String subscriptionExternalId) {
        JsonNode response = http.get()
                .uri(b -> b.path("/authorized_payments/search").queryParam("preapproval_id", subscriptionExternalId)
                        .queryParam("limit", 50).build())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve().body(JsonNode.class);
        List<RemotePayment> result = new ArrayList<>();
        if (response != null) {
            response.path("results").forEach(n -> result.add(payment(n)));
        }
        return result;
    }

    @Override
    public void cancelSubscription(String externalId) {
        http.put().uri("/preapproval/{id}", externalId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .header("X-Idempotency-Key", "cancel-" + externalId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "cancelled"))
                .retrieve().toBodilessEntity();
    }

    @Override
    public boolean verifySignature(WebhookRequest request) {
        return MercadoPagoSignature.verify(request.signature(), request.requestId(), request.dataId(),
                config.getWebhookSecret(), clock.instant(), config.getWebhookTolerance());
    }

    // ------------------------------------------------------------------ helpers

    private Optional<JsonNode> get(String path, String id) {
        try {
            return Optional.ofNullable(http.get().uri(path, id).header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve().body(JsonNode.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    private RemotePayment payment(JsonNode n) {
        JsonNode payment = n.path("payment");
        return new RemotePayment(text(n, "id"), text(n, "preapproval_id"), text(n, "status"),
                payment.isMissingNode() || payment.isNull() ? null : text(payment, "status"),
                decimal(n.path("transaction_amount")), text(n, "currency_id"),
                instant(text(n, "debit_date")), instant(text(n, "last_modified")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static BigDecimal decimal(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.decimalValue();
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

}
