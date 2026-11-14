package com.climind.saas.calculators;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.climind.saas.shared.util.QrCodes;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Cotizadores", description = "Cotizadores del negocio autenticado")
@RestController
@RequestMapping("/api/v1/calculators")
public class CalculatorController {

    private final CalculatorService service;

    public CalculatorController(CalculatorService service) {
        this.service = service;
    }

    @Operation(summary = "Listar cotizadores (sin archivados, salvo que se pidan)")
    @GetMapping
    public List<CalculatorDtos.CalculatorSummary> list(@RequestParam(defaultValue = "false") boolean archived) {
        return service.list(archived);
    }

    @Operation(summary = "Crear un cotizador vacío o desde una plantilla")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CalculatorDtos.CalculatorDetail create(@Valid @RequestBody CalculatorDtos.CreateCalculatorRequest request) {
        return service.create(request);
    }

    @Operation(summary = "Detalle con el borrador y lo que falta para publicar")
    @GetMapping("/{id}")
    public CalculatorDtos.CalculatorDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Guardar el borrador (no cambia lo publicado)")
    @PutMapping("/{id}")
    public CalculatorDtos.CalculatorDetail update(@PathVariable UUID id,
                                                  @Valid @RequestBody CalculatorDtos.UpdateCalculatorRequest request) {
        return service.updateDraft(id, request);
    }

    @Operation(summary = "Publicar el borrador como versión nueva e inmutable")
    @PostMapping("/{id}/publish")
    public CalculatorDtos.PublishResponse publish(@PathVariable UUID id) {
        return service.publish(id);
    }

    @Operation(summary = "Pausar un cotizador publicado")
    @PostMapping("/{id}/pause")
    public CalculatorDtos.CalculatorDetail pause(@PathVariable UUID id) {
        return service.pause(id);
    }

    @Operation(summary = "Reactivar un cotizador pausado")
    @PostMapping("/{id}/resume")
    public CalculatorDtos.CalculatorDetail resume(@PathVariable UUID id) {
        return service.resume(id);
    }

    @Operation(summary = "Archivar (no se borra nada)")
    @PostMapping("/{id}/archive")
    public CalculatorDtos.CalculatorDetail archive(@PathVariable UUID id) {
        return service.archive(id);
    }

    @Operation(summary = "Restaurar un cotizador archivado")
    @PostMapping("/{id}/restore")
    public CalculatorDtos.CalculatorDetail restore(@PathVariable UUID id) {
        return service.restore(id);
    }

    @Operation(summary = "Duplicar como borrador nuevo")
    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public CalculatorDtos.CalculatorDetail duplicate(@PathVariable UUID id) {
        return service.duplicate(id);
    }

    @Operation(summary = "Vista previa del cálculo (no guarda ni consume cupo)")
    @PostMapping("/{id}/preview")
    public CalculatorDtos.PreviewResponse preview(@PathVariable UUID id,
                                                  @Valid @RequestBody CalculatorDtos.PreviewRequest request) {
        return service.preview(id, request);
    }

    @Operation(summary = "Historial de versiones publicadas")
    @GetMapping("/{id}/versions")
    public List<CalculatorDtos.VersionSummary> versions(@PathVariable UUID id) {
        return service.versions(id);
    }

    @Operation(summary = "Código QR del enlace público (SVG para mostrar en pantalla)")
    @GetMapping("/{id}/qr.svg")
    public ResponseEntity<String> qrSvg(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml"))
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(QrCodes.svg(service.get(id).publicUrl()));
    }

    @Operation(summary = "Código QR del enlace público (PNG para imprimir o descargar)")
    @GetMapping("/{id}/qr.png")
    public ResponseEntity<byte[]> qrPng(@PathVariable UUID id) {
        CalculatorDtos.CalculatorDetail detail = service.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("qr-" + detail.slug() + ".png").build().toString())
                .body(QrCodes.png(detail.publicUrl(), 16));
    }
}
