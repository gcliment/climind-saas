package com.climind.saas.assistant;

import com.climind.saas.shared.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Respuestas con OpenAI (Chat Completions), con el mismo enfoque que Control de Gastos: llamada HTTP directa,
 * sin librería extra. Las instrucciones y la documentación van primero y siempre iguales, para que OpenAI
 * reutilice ese prefijo (caché automática de prompts); el contexto del usuario va en un segundo mensaje de
 * sistema. Nunca se envían datos de los clientes de los negocios.
 */
@Slf4j
public class OpenAiAssistantModel implements AssistantModel {

    /** Respuestas de chat: cortas. Alcanza con holgura para pasos numerados. */
    static final int MAX_COMPLETION_TOKENS = 900;

    private final RestClient http;
    private final String model;

    public OpenAiAssistantModel(RestClient.Builder builder, AppProperties.Assistant config) {
        this.model = config.getModel();
        this.http = builder
                .baseUrl(config.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey().strip())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public Optional<String> reply(String stableSystem, String userContext, List<Turn> turns) {
        JsonNode response = http.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request(stableSystem, userContext, turns))
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return Optional.empty();
        }
        JsonNode message = response.path("choices").path(0).path("message");
        if (message.hasNonNull("refusal")) {
            log.info("El asistente declinó responder una consulta");
            return Optional.empty();
        }
        JsonNode usage = response.path("usage");
        log.debug("Asistente: {} tokens de entrada ({} desde caché), {} de salida",
                usage.path("prompt_tokens").asInt(), usage.path("prompt_tokens_details").path("cached_tokens").asInt(),
                usage.path("completion_tokens").asInt());
        String text = message.path("content").isString() ? message.path("content").asString().strip() : "";
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    Map<String, Object> request(String stableSystem, String userContext, List<Turn> turns) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", stableSystem));
        messages.add(Map.of("role", "system", "content", userContext));
        for (Turn turn : turns) {
            messages.add(Map.of("role", turn.fromUser() ? "user" : "assistant", "content", turn.text()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_completion_tokens", MAX_COMPLETION_TOKENS);
        body.put("temperature", 0.3);
        return body;
    }
}
