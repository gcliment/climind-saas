package com.climind.saas.quotes;

import com.climind.saas.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Consultas", description = "Consultas de clientes del negocio autenticado")
@RestController
@RequestMapping("/api/v1/leads")
public class LeadController {

    private final LeadService service;

    public LeadController(LeadService service) {
        this.service = service;
    }

    @Operation(summary = "Listar consultas con filtros, búsqueda y orden (recientes, antiguas, mayor_importe, menor_importe)")
    @GetMapping
    public PageResponse<LeadDtos.LeadSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID calculatorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(new LeadDtos.LeadFilter(status, q, calculatorId, from, to, sort), page, size);
    }

    @Operation(summary = "Cantidad de consultas por estado")
    @GetMapping("/status-counts")
    public List<LeadDtos.StatusOption> statusCounts() {
        return service.statusCounts();
    }

    @Operation(summary = "Exportar consultas a CSV (mismos filtros que el listado, hasta 5000 filas)")
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID calculatorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String sort) {
        String csv = service.exportCsv(new LeadDtos.LeadFilter(status, q, calculatorId, from, to, sort));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("consultas.csv").build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "Detalle de una consulta, con el desglose guardado y la línea de tiempo")
    @GetMapping("/{id}")
    public LeadDtos.LeadDetail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @Operation(summary = "Cambiar el estado (con nota opcional)")
    @PutMapping("/{id}/status")
    public LeadDtos.LeadDetail changeStatus(@PathVariable UUID id,
                                            @Valid @RequestBody LeadDtos.ChangeStatusRequest request) {
        return service.changeStatus(id, request);
    }

    @Operation(summary = "Agregar una nota")
    @PostMapping("/{id}/notes")
    public LeadDtos.LeadDetail addNote(@PathVariable UUID id, @Valid @RequestBody LeadDtos.NoteRequest request) {
        return service.addNote(id, request);
    }

    @Operation(summary = "Eliminar los datos personales de la consulta (no se puede deshacer)")
    @PostMapping("/{id}/anonymize")
    public LeadDtos.LeadDetail anonymize(@PathVariable UUID id) {
        return service.anonymize(id);
    }

    @Operation(summary = "Descargar la cotización de la consulta en PDF")
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        QuoteDocuments.Pdf pdf = service.pdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pdf.filename()).build().toString())
                .body(pdf.content());
    }
}
