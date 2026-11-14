package com.climind.saas.shared.web;

import com.climind.saas.shared.config.AppProperties;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * robots.txt y sitemap.xml con las URLs públicas configuradas (dependen del entorno). Se indexan las páginas
 * comerciales y de ayuda; nunca el panel, la API ni las cotizaciones emitidas (son privadas).
 */
@Hidden
@RestController
public class SeoController {

    /** Páginas públicas indexables de Climind Cotiza (rutas relativas a la URL pública). */
    static final List<String> PUBLIC_PAGES = List.of("/", "/precios", "/ayuda", "/registro",
            "/legal/terminos", "/legal/privacidad");

    private final AppProperties properties;

    public SeoController(AppProperties properties) {
        this.properties = properties;
    }

    @GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> robots() {
        String body = """
                User-agent: *
                Disallow: /app/
                Disallow: /api/
                Disallow: /cotizacion/
                Disallow: /emails/
                Allow: /

                Sitemap: %s/sitemap.xml
                """.formatted(properties.publicUrlWithoutTrailingSlash());
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePublic()).body(body);
    }

    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    public ResponseEntity<String> sitemap() {
        String base = properties.publicUrlWithoutTrailingSlash();
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                """);
        for (String page : PUBLIC_PAGES) {
            url(xml, base + page);
        }
        for (String article : FrontendConfig.HELP_ARTICLES) {
            url(xml, base + "/ayuda/" + article);
        }
        url(xml, properties.getPortalUrl());
        xml.append("</urlset>\n");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePublic())
                .body(xml.toString());
    }

    private static void url(StringBuilder xml, String loc) {
        xml.append("  <url><loc>").append(StaticPages.escape(loc)).append("</loc></url>\n");
    }
}
