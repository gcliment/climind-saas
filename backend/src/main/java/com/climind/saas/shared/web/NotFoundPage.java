package com.climind.saas.shared.web;

import org.springframework.stereotype.Component;

/** Página 404 del frontend ({@code 404.html} en la primera ubicación estática que la tenga). */
@Component
public class NotFoundPage {

    private static final String FALLBACK = """
            <!doctype html><html lang="es"><meta charset="utf-8"><title>Página no encontrada</title>
            <body><h1>No encontramos esta página</h1><p><a href="/">Volver al inicio</a></p></body></html>""";

    private final StaticPages pages;

    public NotFoundPage(StaticPages pages) {
        this.pages = pages;
    }

    public String html() {
        return pages.read("404.html").orElse(FALLBACK);
    }

    /**
     * 404 de un cotizador público ({@code /c/...}): el mismo mensaje sirve si el enlace está mal, si el cotizador
     * no está publicado o si el negocio no está disponible (no revela cuál de los casos es).
     */
    public String calculatorHtml() {
        return html()
                .replace("Página no encontrada", "Cotizador no disponible")
                .replace("No encontramos esta página", "Este cotizador no está disponible")
                .replace("Puede que el enlace esté incompleto o que la página se haya movido. Si llegaste desde el enlace de un negocio, pediles que te lo vuelvan a compartir.",
                        "Puede que el negocio lo haya desactivado por ahora o que el enlace esté incompleto. Si te lo compartió un negocio, consultales directamente.");
    }
}
