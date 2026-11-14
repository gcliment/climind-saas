package com.climind.saas.businesses;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@Tag(name = "Público")
@RestController
public class PublicAssetController {

    private final BusinessLogoService logos;

    public PublicAssetController(BusinessLogoService logos) {
        this.logos = logos;
    }

    @Operation(summary = "Logo de un negocio (público: se muestra en sus cotizadores)")
    @GetMapping("/api/v1/public/assets/{id}")
    public ResponseEntity<Resource> logo(@PathVariable UUID id) {
        return logos.publicLogo(id)
                .map(file -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(file.contentType()))
                        // El id cambia con cada logo nuevo: se puede cachear como inmutable.
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .body(file.resource()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
