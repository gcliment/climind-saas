package com.climind.saas.subscriptions;

import com.climind.saas.shared.web.ApiException;
import com.climind.saas.subscriptions.payments.PaymentProvider;
import com.climind.saas.subscriptions.payments.PaymentProvider.WebhookRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.Map;

/**
 * Recepción de notificaciones del proveedor de pagos:
 * <ol>
 *   <li>Valida la firma (si no es válida: 401 y no se procesa nada).</li>
 *   <li>Deduplica por id de notificación (Mercado Pago reintenta y puede repetir envíos).</li>
 *   <li>Sincroniza consultando el recurso al proveedor (nunca confía en el contenido de la notificación).</li>
 * </ol>
 * Si el procesamiento falla se responde 500 para que el proveedor reintente; la falla queda registrada y la
 * conciliación periódica también la corrige.
 */
@Slf4j
@Service
public class WebhookService {

    private final PaymentProvider provider;
    private final SubscriptionSync sync;
    private final TransactionTemplate requiresNew;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;

    public WebhookService(PaymentProvider provider, SubscriptionSync sync, TransactionTemplate transactions, Clock clock) {
        this.provider = provider;
        this.sync = sync;
        this.requiresNew = new TransactionTemplate(transactions.getTransactionManager());
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public String receive(String signature, String requestId, Map<String, String> query, String body) {
        JsonNode payload = parse(body);
        String type = first(query.get("type"), query.get("topic"), text(payload, "type"), text(payload, "topic"));
        String dataId = first(query.get("data.id"), query.get("id"), text(payload.path("data"), "id"));
        String notificationId = text(payload, "id");
        WebhookRequest request = new WebhookRequest(signature, requestId, dataId, type, notificationId, body);
        if (!provider.verifySignature(request)) {
            log.warn("Notificación de pagos con firma inválida (tipo {}, recurso {})", type, dataId);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Firma inválida.");
        }
        String key = notificationId != null ? "notification:" + notificationId
                : requestId != null ? "request:" + requestId : "resource:" + type + ":" + dataId;
        try {
            return sync.handleNotification(key, type, dataId, summary(payload, type, dataId));
        } catch (RuntimeException e) {
            log.warn("No se pudo procesar la notificación {} ({}): {}", key, type, e.getMessage());
            requiresNew.executeWithoutResult(status -> sync.recordEvent(key + ":failed:" + clock.instant().toEpochMilli(),
                    type == null ? "unknown" : type, dataId, null, "FAILED", e.getClass().getSimpleName() + ": " + e.getMessage()));
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_FAILED",
                    "No se pudo procesar la notificación. Se reintentará.");
        }
    }

    /** Se guarda solo lo necesario para auditar (ids y tipo), no el cuerpo completo. */
    private String summary(JsonNode payload, String type, String dataId) {
        ObjectNode node = json.createObjectNode();
        node.put("type", type);
        node.put("dataId", dataId);
        if (payload.has("action")) {
            node.put("action", payload.get("action").asString());
        }
        if (payload.has("live_mode")) {
            node.put("liveMode", payload.get("live_mode").asBoolean());
        }
        if (payload.has("date_created")) {
            node.put("dateCreated", payload.get("date_created").asString());
        }
        return json.writeValueAsString(node);
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
        } catch (RuntimeException e) {
            return json.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
