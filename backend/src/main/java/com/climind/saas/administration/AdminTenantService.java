package com.climind.saas.administration;

import com.climind.saas.account.AccountDeletionService;
import com.climind.saas.businesses.Industry;
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
    private final AccountDeletionService deletions;
    private final Clock clock;

    public AdminTenantService(JdbcTemplate jdbc, TenantService tenants, EntitlementService entitlements,
                              UsageService usage, AuditLogService audit, AccountDeletionService deletions,
                              Clock clock) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.entitlements = entitlements;
        this.usage = usage;
        this.audit = audit;
        this.deletions = deletions;
        this.clock = clock;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminTenant(UUID id, String businessName, String slug, String ownerEmail, String status,
                              String suspendedReason, Instant createdAt, String planCode, String planName, boolean paid,
                              int quotesUsed, int quotesLimit, long publishedCalculators, int publishedLimit,
                              Instant deletionScheduledFor) {
    }

    /**
     * Ficha de un negocio para el seguimiento comercial: quién es, cómo contactarlo y hasta dónde llegó
     * (datos cargados, guía inicial, cotizadores, cotizaciones y consultas). Solo lectura.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminTenantDetail(UUID id, String businessName, String slug, String status, Instant createdAt,
                                    Instant onboardingCompletedAt, Instant deletionScheduledFor,
                                    String planName, boolean paid, Contact contact, Owner owner,
                                    Calculators calculators, Activity activity) {
    }

    /** Datos que el negocio cargó en «Mi negocio» (null si todavía no los completó). */
    public record Contact(String industry, String industryLabel, String whatsapp, String phone, String email,
                          String website) {
    }

    public record Owner(String firstName, String lastName, String email, boolean emailVerified,
                        Instant registeredAt, Instant lastLoginAt) {
    }

    /** Cotizadores sin contar los archivados. */
    public record Calculators(long total, long drafts, long published, long paused, Instant lastEditedAt) {
    }

    public record Activity(int quotesThisMonth, long quotesTotal, long leadsTotal, Instant lastQuoteAt,
                           Instant lastLeadAt) {
    }

    public record SuspendRequest(
            @NotBlank(message = "Indicá el motivo de la suspensión.")
            @Size(max = 300, message = "El motivo admite hasta 300 caracteres.")
            String reason) {
    }

    /** Eliminación definitiva: se confirma escribiendo ELIMINAR; {@code notifyOwner} manda el email de baja. */
    public record DeleteRequest(
            @NotBlank(message = "Escribí ELIMINAR para confirmar.")
            String confirmation,
            boolean notifyOwner) {
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
                  AND (CASE WHEN ? = '' THEN t.status <> 'DELETED' ELSE t.status = ? END)
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

    @Transactional(readOnly = true)
    public AdminTenantDetail detail(UUID tenantId) {
        List<AdminTenantDetail> found = jdbc.query("""
                        SELECT t.id, coalesce(p.business_name, t.name) AS name, t.slug, t.status, t.created_at,
                               t.onboarding_completed_at, t.deletion_scheduled_for,
                               p.industry, p.whatsapp, p.phone, p.contact_email, p.website,
                               u.first_name, u.last_name, u.email, u.email_verified_at, u.created_at AS registered_at,
                               u.last_login_at
                        FROM tenants t
                        LEFT JOIN business_profiles p ON p.tenant_id = t.id
                        LEFT JOIN LATERAL (SELECT u.* FROM tenant_memberships m JOIN users u ON u.id = m.user_id
                                           WHERE m.tenant_id = t.id AND m.role = 'OWNER'
                                           ORDER BY m.created_at LIMIT 1) u ON true
                        WHERE t.id = ? AND NOT t.demo
                        """,
                (rs, i) -> {
                    Entitlements e = entitlements.forTenant(tenantId);
                    String industry = rs.getString("industry");
                    Contact contact = new Contact(industry, industryLabel(industry), rs.getString("whatsapp"),
                            rs.getString("phone"), rs.getString("contact_email"), rs.getString("website"));
                    Owner owner = rs.getString("email") == null ? null
                            : new Owner(rs.getString("first_name"), rs.getString("last_name"), rs.getString("email"),
                            rs.getTimestamp("email_verified_at") != null, instant(rs, "registered_at"),
                            instant(rs, "last_login_at"));
                    return new AdminTenantDetail(tenantId, rs.getString("name"), rs.getString("slug"),
                            rs.getString("status"), instant(rs, "created_at"), instant(rs, "onboarding_completed_at"),
                            instant(rs, "deletion_scheduled_for"), e.planName(), e.paid(), contact, owner,
                            calculators(tenantId), activity(tenantId, e));
                }, tenantId);
        if (found.isEmpty()) {
            throw ApiException.notFound("No encontramos ese negocio.");
        }
        return found.getFirst();
    }

    private Calculators calculators(UUID tenantId) {
        return jdbc.queryForObject("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE status = 'BORRADOR') AS drafts,
                               count(*) FILTER (WHERE status = 'PUBLICADO') AS published,
                               count(*) FILTER (WHERE status = 'PAUSADO') AS paused,
                               max(updated_at) AS last_edited
                        FROM calculators WHERE tenant_id = ? AND status <> 'ARCHIVADO'
                        """,
                (rs, i) -> new Calculators(rs.getLong("total"), rs.getLong("drafts"), rs.getLong("published"),
                        rs.getLong("paused"), instant(rs, "last_edited")), tenantId);
    }

    private Activity activity(UUID tenantId, Entitlements e) {
        return jdbc.queryForObject("""
                        SELECT (SELECT count(*) FROM quote_simulations WHERE tenant_id = ?) AS quotes,
                               (SELECT max(created_at) FROM quote_simulations WHERE tenant_id = ?) AS last_quote,
                               (SELECT count(*) FROM quote_requests WHERE tenant_id = ?) AS leads,
                               (SELECT max(created_at) FROM quote_requests WHERE tenant_id = ?) AS last_lead
                        """,
                (rs, i) -> new Activity(usage.quoteUsage(tenantId, e).used(), rs.getLong("quotes"), rs.getLong("leads"),
                        instant(rs, "last_quote"), instant(rs, "last_lead")),
                tenantId, tenantId, tenantId, tenantId);
    }

    private static String industryLabel(String code) {
        if (code == null) {
            return null;
        }
        try {
            return Industry.valueOf(code).label();
        } catch (IllegalArgumentException unknown) {
            return code;
        }
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
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

    /** Elimina el negocio y anonimiza a sus usuarios, sin plazo para arrepentirse. No se puede deshacer. */
    public void deleteDefinitively(UUID tenantId, DeleteRequest request) {
        if (!"ELIMINAR".equals(request.confirmation().strip().toUpperCase(Locale.ROOT))) {
            throw ApiException.field("confirmation", "Escribí ELIMINAR para confirmar.");
        }
        deletions.deleteNowByAdmin(tenantId, CurrentUser.require().userId(), request.notifyOwner());
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
