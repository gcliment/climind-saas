package com.climind.saas.calculators;

import com.climind.saas.pricing.CalculatorConfig;
import com.climind.saas.shared.util.Tokens;
import com.climind.saas.shared.web.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conversión JSON ↔ {@link CalculatorConfig} con reglas estrictas: propiedades desconocidas rechazadas,
 * decimales como BigDecimal (nunca double) y serialización canónica (claves ordenadas) para calcular el hash
 * de cada versión.
 */
@Component
public class ConfigJson {

    private final JsonMapper strict = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public CalculatorConfig parse(String json) {
        return strict.readValue(json, CalculatorConfig.class);
    }

    /** Parseo de lo que envía el constructor; un JSON con forma inválida es un 400 entendible. */
    public CalculatorConfig parseRequest(JsonNode node) {
        if (node == null || node.isNull()) {
            throw ApiException.field("config", "Falta la configuración del cotizador.");
        }
        try {
            return strict.treeToValue(node, CalculatorConfig.class);
        } catch (JacksonException e) {
            throw ApiException.badRequest("CONFIG_MALFORMED",
                    "La configuración del cotizador tiene un formato inválido. Recargá la página y volvé a intentar.");
        }
    }

    public String write(Object value) {
        return strict.writeValueAsString(value);
    }

    public JsonNode tree(String json) {
        return strict.readTree(json);
    }

    public <T> T read(String json, Class<T> type) {
        return strict.readValue(json, type);
    }

    public <T> T convert(JsonNode node, Class<T> type) {
        return strict.treeToValue(node, type);
    }

    /** Hash estable del contenido publicable (mismo contenido → mismo hash). */
    public String hash(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            canonical.append(part instanceof String s ? s : strict.writeValueAsString(part)).append('\u0000');
        }
        return Tokens.sha256Hex(canonical.toString());
    }
}
