package com.climind.saas.calculators;

import com.climind.saas.pricing.CalculatorConfig;
import com.climind.saas.pricing.ConfigIssue;
import com.climind.saas.pricing.ConfigValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plantillas de cotizadores ({@code classpath:calculator-templates/*.json}), versionadas con el código.
 * Se validan al arrancar con las mismas reglas que una publicación: una plantilla rota no llega a producción.
 * Los precios son EJEMPLOS editables, no precios de mercado.
 */
@Slf4j
@Component
public class TemplateCatalog {

    /** Objetivo elegido en el onboarding (paso 2). */
    public enum Objective { SERVICIOS, PRODUCTOS, CANTIDAD, CARACTERISTICAS }

    public record Template(String code, String name, String industry, String description, List<Objective> objectives,
                           int order, String calculatorName, String calculatorDescription, CalculatorConfig config) {
    }

    private final Map<String, Template> templates;

    public TemplateCatalog(ConfigJson json) throws IOException {
        Map<String, Template> loaded = new LinkedHashMap<>();
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:calculator-templates/*.json");
        for (Resource resource : resources) {
            Template template;
            try (InputStream in = resource.getInputStream()) {
                template = json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8), Template.class);
            }
            List<ConfigIssue> issues = ConfigValidator.validateForPublish(template.config());
            if (!issues.isEmpty()) {
                throw new IllegalStateException("Plantilla inválida " + resource.getFilename() + ": " + issues);
            }
            if (loaded.put(template.code(), template) != null) {
                throw new IllegalStateException("Código de plantilla duplicado: " + template.code());
            }
        }
        this.templates = loaded;
        log.info("Plantillas de cotizadores cargadas: {}", loaded.keySet());
    }

    public List<Template> all() {
        return templates.values().stream().sorted(Comparator.comparingInt(Template::order)).toList();
    }

    public Optional<Template> find(String code) {
        return Optional.ofNullable(code).map(templates::get);
    }
}
