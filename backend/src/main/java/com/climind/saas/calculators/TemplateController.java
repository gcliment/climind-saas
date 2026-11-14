package com.climind.saas.calculators;

import com.climind.saas.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Plantillas")
@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {

    private final TemplateCatalog catalog;
    private final ConfigJson json;

    public TemplateController(TemplateCatalog catalog, ConfigJson json) {
        this.catalog = catalog;
        this.json = json;
    }

    @Operation(summary = "Plantillas disponibles")
    @GetMapping
    public List<CalculatorDtos.TemplateSummary> list() {
        return catalog.all().stream().map(t -> new CalculatorDtos.TemplateSummary(t.code(), t.name(), t.industry(),
                industryLabel(t.industry()), t.description(), t.objectives().stream().map(Enum::name).toList(),
                t.config().questions().size(), t.calculatorName())).toList();
    }

    @Operation(summary = "Detalle de una plantilla, con su configuración")
    @GetMapping("/{code}")
    public CalculatorDtos.TemplateDetail get(@PathVariable String code) {
        TemplateCatalog.Template t = catalog.find(code)
                .orElseThrow(() -> ApiException.notFound("No encontramos esa plantilla."));
        return new CalculatorDtos.TemplateDetail(t.code(), t.name(), t.description(), t.calculatorName(),
                t.calculatorDescription(), json.tree(json.write(t.config())));
    }

    private static String industryLabel(String code) {
        try {
            return com.climind.saas.businesses.Industry.valueOf(code).label();
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
