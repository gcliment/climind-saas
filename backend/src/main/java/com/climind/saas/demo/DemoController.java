package com.climind.saas.demo;

import com.climind.saas.pricing.PriceBreakdown;
import com.climind.saas.shared.web.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Demos públicas para la landing (sin sesión). */
@Tag(name = "Público", description = "Cotizador público para los clientes de cada negocio")
@RestController
@RequestMapping("/api/v1/public/demos")
public class DemoController {

    private final DemoService demos;
    private final ClientIpResolver ipResolver;

    public DemoController(DemoService demos, ClientIpResolver ipResolver) {
        this.demos = demos;
        this.ipResolver = ipResolver;
    }

    public record EstimateRequest(@Size(max = 60, message = "Demasiadas respuestas.") Map<String, Object> answers) {
    }

    @Operation(summary = "Cotizadores de demostración (negocio ficticio, precios de ejemplo)")
    @GetMapping
    public ResponseEntity<List<DemoService.Demo>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(demos.list());
    }

    @Operation(summary = "Estimación en vivo de una demo (no guarda nada)")
    @PostMapping("/{calculatorSlug}/estimate")
    public PriceBreakdown estimate(@PathVariable String calculatorSlug, @Valid @RequestBody EstimateRequest request,
                                   HttpServletRequest http) {
        return demos.estimate(calculatorSlug, request.answers(), ipResolver.resolve(http));
    }
}
