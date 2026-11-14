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
}
