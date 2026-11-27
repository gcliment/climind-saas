package com.climind.saas.quotes;

import com.climind.saas.businesses.PublicBusinesses;
import com.climind.saas.calculators.PublicCalculators;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.web.NotFoundPage;
import com.climind.saas.shared.web.StaticPages;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Optional;

/**
 * Página pública de cada cotizador ({@code /c/{negocio}/{cotizador}}). Es el HTML estático del frontend con
 * título y metadatos Open Graph del cotizador: así el enlace compartido por WhatsApp o redes muestra una vista
 * previa con el nombre del negocio. El formulario y los precios los carga el JS desde la API.
 */
@Hidden
@Controller
public class PublicPageController {

    private static final String PAGE = "cotizar.html";

    private final PublicCalculators calculators;
    private final PublicBusinesses businesses;
    private final StaticPages pages;
    private final NotFoundPage notFound;
    private final AppProperties properties;

    public PublicPageController(PublicCalculators calculators, PublicBusinesses businesses, StaticPages pages,
                                NotFoundPage notFound, AppProperties properties) {
        this.calculators = calculators;
        this.businesses = businesses;
        this.pages = pages;
        this.notFound = notFound;
        this.properties = properties;
    }

    @GetMapping(value = "/c/{tenantSlug}/{calculatorSlug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String tenantSlug, @PathVariable String calculatorSlug) {
        Optional<PublicCalculators.PublishedCalculator> found = calculators.find(tenantSlug, calculatorSlug);
        Optional<String> template = pages.read(PAGE);
        if (found.isEmpty() || template.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(new MediaType("text", "html",
                    java.nio.charset.StandardCharsets.UTF_8)).body(notFound.calculatorHtml());
        }
        PublicCalculators.PublishedCalculator pc = found.get();
        Optional<PublicBusinesses.PublicBusiness> business = businesses.find(pc.tenantId());
        String businessName = business.map(PublicBusinesses.PublicBusiness::name).orElse("");
        String title = pc.name() + (businessName.isBlank() ? "" : " | " + businessName);
        String description = pc.description() != null && !pc.description().isBlank() ? pc.description()
                : "Calculá tu presupuesto estimado en segundos" + (businessName.isBlank() ? "." : " con " + businessName + ".");
        String image = business.map(PublicBusinesses.PublicBusiness::logoUrl)
                .filter(url -> Boolean.TRUE.equals(pc.appearance().showLogo()))
                .map(url -> properties.publicUrlWithoutTrailingSlash() + url).orElse("");

        String html = template.get();
        if (image.isEmpty()) {
            html = html.replaceAll("\\s*<meta property=\"og:image\"[^>]*>", "");
        }
        html = html
                .replace("{{title}}", StaticPages.escape(title))
                .replace("{{description}}", StaticPages.escape(description))
                .replace("{{url}}", StaticPages.escape(pc.publicUrl()))
                .replace("{{image}}", StaticPages.escape(image));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noCache())
                .body(html);
    }
}
