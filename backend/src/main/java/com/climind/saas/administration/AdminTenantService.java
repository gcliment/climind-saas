package com.climind.saas.administration;

import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.security.CurrentUser;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.shared.web.PageResponse;
import com.climind.saas.subscriptions.EntitlementService;
import com.climind.saas.subscriptions.Entitlements;
import com.climind.saas.subscriptions.UsageService;
import com.climind.saas.tenants.Tenant;
import com.climind.saas.tenants.TenantService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Negocios desde el panel SUPER_ADMIN: búsqueda, plan, uso y suspensión administrativa (auditada). */
@Service
public class AdminTenantService {

    private static final int MAX_PAGE = 50;

    private final JdbcTemplate jdbc;
    private final TenantService tenants;
    private final EntitlementService entitlements;
    private final UsageService usage;
    private final AuditLogService audit;
    private final Clock clock;

    public AdminTenantService(JdbcTemplate jdbc, TenantService tenants, EntitlementService entitlements,
                              UsageService usage, AuditLogService audit, Clock clock) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.entitlements = entitlements;
        this.usage = usage;
        this.audit = audit;
        this.clock = clock;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminTenant(UUID id, String businessName, String slug, String ownerEmail, String status,
                              String suspendedReason, Instant createdAt, String planCode, String planName, boolean paid,
                              int quotesUsed, int quotesLimit, long publishedCalculators, int publishedLimit,
                              Instant deletionScheduledFor) {
    }

    public record SuspendRequest(
            @NotBlank(message = "Indicá el motivo de la suspensión.")
            @Size(max = 300, message = "El motivo admite hasta 300 caracteres.")
            String reason) {
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminTenant> list(String q, String status, int page, int size) {
        int limit = Math.min(Math.max(size, 1), MAX_PAGE);
        int offset = Math.max(page, 0) * limit;
        String text = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        String like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String st = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        String where = """
                FROM tenants t
                LEFT JOIN business_profiles p ON p.tenant_id = t.id
                LEFT JOIN LATERAL (SELECT u.email FROM tenant_memberships m JOIN users u ON u.id = m.user_id
                                   WHERE m.tenant_id = t.id AND m.role = 'OWNER' ORDER BY m.created_at LIMIT 1) o ON true
                WHERE NOT t.demo
                  AND (? = '' OR lower(coalesce(p.business_name, t.name)) LIKE ? OR t.slug LIKE ? OR lower(o.email) LIKE ?)
                  AND (? = '' OR t.status = ?)
                """;
        Long total = jdbc.queryForObject("SELECT count(*) " + where, Long.class, text, like, like, like, st, st);
        List<AdminTenant> rows = new ArrayList<>();
        jdbc.query("""
                        SELECT t.id, coalesce(p.business_name, t.name) AS name, t.slug, o.email, t.status, t.suspended_reason,
                               t.created_at, t.deletion_scheduled_for
                        """ + where + " ORDER BY t.created_at DESC LIMIT ? OFFSET ?",
                rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    Entitlements e = entitlements.forTenant(id);
                    UsageService.Usage quotes = usage.quoteUsage(id, e);
                    rows.add(new AdminTenant(id, rs.getString("name"), rs.getString("slug"), rs.getString("email"),
                            rs.getString("status"), rs.getString("suspended_reason"),
                            rs.getTimestamp("created_at").toInstant(), e.planCode(), e.planName(), e.paid(),
                            quotes.used(), quotes.limit(), usage.publishedCalculators(id), e.maxPublishedCalculators(),
                            rs.getTimestamp("deletion_scheduled_for") == null ? null
                                    : rs.getTimestamp("deletion_scheduled_for").toInstant()));
                }, text, like, like, like, st, st, limit, offset);
        long count = total == null ? 0 : total;
        return new PageResponse<>(rows, Math.max(page, 0), limit, count, (int) Math.ceil(count / (double) limit));
    }

    @Transactional
    public void suspend(UUID tenantId, SuspendRequest request) {
        Tenant tenant = tenants.find(tenantId).orElseThrow(() -> ApiException.notFound("No existe ese negocio."));
        if (!tenant.isActive()) {
            return;
        }
        tenants.suspend(tenantId, request.reason().strip(), clock.instant());
        audit.record(AuditAction.TENANT_SUSPENDED, CurrentUser.require().userId(), tenantId, "tenant",
                tenantId.toString(), Map.of("reason", request.reason().strip()));
    }

    @Transactional
    public void reactivate(UUID tenantId) {
        Tenant tenant = tenants.find(tenantId).orElseThrow(() -> ApiException.notFound("No existe ese negocio."));
        if (tenant.isActive()) {
            return;
        }
        tenants.reactivate(tenantId);
        audit.record(AuditAction.TENANT_REACTIVATED, CurrentUser.require().userId(), tenantId, "tenant",
                tenantId.toString(), Map.of());
    }
}
