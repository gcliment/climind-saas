package com.climind.saas.administration;

import com.climind.saas.shared.web.PageResponse;
import com.climind.saas.subscriptions.BillingAdminService;
import com.climind.saas.subscriptions.BillingJobs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Panel SUPER_ADMIN (solo Climind-IA). Protegido por rol en SecurityConfig: {@code /api/v1/admin/**}. */
@Tag(name = "Administración")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService service;
    private final AdminTenantService tenants;
    private final BillingAdminService billing;

    public AdminController(AdminService service, AdminTenantService tenants, BillingAdminService billing) {
        this.service = service;
        this.tenants = tenants;
        this.billing = billing;
    }

    @Operation(summary = "Métricas globales de la plataforma (incluye MRR estimado y lo efectivamente cobrado)")
    @GetMapping("/summary")
    public AdminService.PlatformSummary summary() {
        return service.summary();
    }

    // ------------------------------------------------------------------ negocios

    @Operation(summary = "Negocios con plan, uso y estado (búsqueda por nombre, dirección o email del dueño)")
    @GetMapping("/tenants")
    public PageResponse<AdminTenantService.AdminTenant> tenants(@RequestParam(required = false) String q,
                                                                @RequestParam(required = false) String status,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return tenants.list(q, status, page, size);
    }

    @Operation(summary = "Suspender un negocio (motivo obligatorio; queda auditado)")
    @PostMapping("/tenants/{id}/suspend")
    public ResponseEntity<Void> suspend(@PathVariable UUID id, @Valid @RequestBody AdminTenantService.SuspendRequest request) {
        tenants.suspend(id, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reactivar un negocio suspendido")
    @PostMapping("/tenants/{id}/reactivate")
    public ResponseEntity<Void> reactivate(@PathVariable UUID id) {
        tenants.reactivate(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ planes

    @Operation(summary = "Planes (con la cantidad de suscripciones vigentes de cada uno)")
    @GetMapping("/plans")
    public List<BillingAdminService.AdminPlan> plans() {
        return billing.plans();
    }

    @Operation(summary = "Crear un plan pago")
    @PostMapping("/plans")
    public List<BillingAdminService.AdminPlan> createPlan(@Valid @RequestBody BillingAdminService.PlanRequest request) {
        return billing.createPlan(request);
    }

    @Operation(summary = "Editar un plan (solo afecta contrataciones nuevas y al plan gratuito)")
    @PutMapping("/plans/{id}")
    public List<BillingAdminService.AdminPlan> updatePlan(@PathVariable UUID id,
                                                          @Valid @RequestBody BillingAdminService.PlanRequest request) {
        return billing.updatePlan(id, request);
    }

    // ------------------------------------------------------------------ suscripciones y pagos

    @Operation(summary = "Suscripciones (filtro por estado)")
    @GetMapping("/subscriptions")
    public PageResponse<BillingAdminService.AdminSubscription> subscriptions(@RequestParam(required = false) String status,
                                                                             @RequestParam(defaultValue = "0") int page,
                                                                             @RequestParam(defaultValue = "20") int size) {
        return billing.subscriptions(status, page, size);
    }

    @Operation(summary = "Pagos (filtro por estado, p. ej. REJECTED para fallos de cobro)")
    @GetMapping("/payments")
    public PageResponse<BillingAdminService.AdminPayment> payments(@RequestParam(required = false) String status,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        return billing.payments(status, page, size);
    }

    @Operation(summary = "Estado de la integración de pagos y diferencias de conciliación")
    @GetMapping("/billing/health")
    public BillingAdminService.IntegrationHealth billingHealth() {
        return billing.health();
    }

    @Operation(summary = "Ejecutar la conciliación ahora")
    @PostMapping("/billing/reconcile")
    public BillingJobs.Report reconcile() {
        return billing.reconcileNow();
    }
}
