package com.climind.saas.subscriptions;

import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.security.CurrentUser;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.shared.web.PageResponse;
import com.climind.saas.subscriptions.payments.PaymentProvider;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Facturación desde el panel SUPER_ADMIN: planes, suscripciones, pagos, estado de la integración, conciliación
 * y métricas de ingresos.
 * <p>
 * Editar un plan solo afecta a contrataciones nuevas y al plan gratuito: cada suscripción paga conserva el precio
 * y los límites que contrató (y Mercado Pago sigue cobrando el monto con el que se creó la suscripción).
 */
@Service
public class BillingAdminService {

    private final SubscriptionPlanRepository plans;
    private final TenantSubscriptionRepository subscriptions;
    private final PaymentTransactionRepository payments;
    private final PaymentProvider provider;
    private final BillingJobs jobs;
    private final AuditLogService audit;
    private final JdbcTemplate jdbc;
    private final com.climind.saas.shared.config.AppProperties properties;
    private final Clock clock;

    public BillingAdminService(SubscriptionPlanRepository plans, TenantSubscriptionRepository subscriptions,
                               PaymentTransactionRepository payments, PaymentProvider provider, BillingJobs jobs,
                               AuditLogService audit, JdbcTemplate jdbc,
                               com.climind.saas.shared.config.AppProperties properties, Clock clock) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.provider = provider;
        this.jobs = jobs;
        this.audit = audit;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ DTOs

    public record AdminPlan(UUID id, String code, String name, String description, BigDecimal price, String currency,
                            String billingPeriod, boolean free, int maxPublishedCalculators, int maxMonthlyQuotes,
                            boolean csvExport, boolean customBranding, boolean leadEmailNotifications,
                            boolean activeForNew, int sortOrder, int lockVersion, long activeSubscriptions) {
    }

    public record PlanRequest(
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,29}$", message = "Usá letras, números o guion bajo (2 a 30).")
            String code,
            @NotBlank(message = "Poné un nombre.") @Size(max = 60) String name,
            @Size(max = 300) String description,
            @NotNull(message = "Indicá el precio.") @DecimalMin(value = "0", message = "El precio no puede ser negativo.")
            BigDecimal price,
            @NotNull @Min(1) @Max(1000) Integer maxPublishedCalculators,
            @NotNull @Min(1) @Max(10_000_000) Integer maxMonthlyQuotes,
            boolean csvExport,
            boolean customBranding,
            boolean leadEmailNotifications,
            boolean activeForNew,
            @Min(0) @Max(1000) int sortOrder,
            Integer lockVersion) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminSubscription(UUID id, UUID tenantId, String businessName, String planName, String status,
                                    String provider, String externalId, String providerStatus, BigDecimal price,
                                    Instant currentPeriodEnd, Instant graceUntil, Instant lastPaymentAt,
                                    Instant lastSyncedAt, Instant createdAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminPayment(UUID id, UUID tenantId, String businessName, String status, String providerStatus,
                               BigDecimal amount, String currency, String externalId, Instant chargeDate,
                               Instant paidAt, Instant createdAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProviderEvent(String type, String resourceId, String result, String detail, Instant receivedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record IntegrationHealth(String provider, boolean configured, boolean webhookSecretConfigured,
                                    Instant lastNotificationAt, long failedLast7Days, Instant lastReconciliationAt,
                                    String lastReconciliationResult, List<ProviderEvent> recentCorrections,
                                    List<ProviderEvent> recentFailures) {
    }

    /**
     * @param estimatedMrr MRR estimado: precio mensual de las suscripciones que se van a renovar (ACTIVE y
     *                     PAST_DUE en gracia). No es lo cobrado.
     * @param collectedLast30Days pagos efectivamente aprobados en los últimos 30 días
     */
    public record RevenueMetrics(long paidSubscribers, long canceledLast30Days, long pastDue,
                                 BigDecimal estimatedMrr, BigDecimal collectedLast30Days, long failedPaymentsLast30Days) {
    }

    // ------------------------------------------------------------------ planes

    @Transactional(readOnly = true)
    public List<AdminPlan> plans() {
        Map<UUID, Long> active = new java.util.HashMap<>();
        jdbc.query("SELECT plan_id, count(*) FROM tenant_subscriptions WHERE status IN ('ACTIVE','PAST_DUE','PAUSED','TRIALING') GROUP BY plan_id",
                rs -> {
                    active.put(rs.getObject(1, UUID.class), rs.getLong(2));
                });
        return plans.findAllByOrderBySortOrderAscPriceAsc().stream().map(p -> new AdminPlan(p.getId(), p.getCode(),
                p.getName(), p.getDescription(), p.getPrice(), p.getCurrency(), p.getBillingPeriod().name(), p.isFree(),
                p.getMaxPublishedCalculators(), p.getMaxMonthlyQuotes(), p.isCsvExport(), p.isCustomBranding(),
                p.isLeadEmailNotifications(), p.isActiveForNew(), p.getSortOrder(), p.getLockVersion(),
                active.getOrDefault(p.getId(), 0L))).toList();
    }

    @Transactional
    public List<AdminPlan> createPlan(PlanRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw ApiException.field("code", "Indicá un código para el plan.");
        }
        String code = request.code().strip().toUpperCase(Locale.ROOT);
        if (plans.existsByCode(code)) {
            throw ApiException.field("code", "Ya existe un plan con ese código.");
        }
        if (request.price().signum() <= 0) {
            throw ApiException.field("price", "Un plan pago necesita un precio mayor a cero.");
        }
        SubscriptionPlan plan = new SubscriptionPlan(code, request.name().strip(), scale(request.price()), clock.instant());
        apply(plan, request);
        plans.save(plan);
        audit.record(AuditAction.PLAN_CREATED, CurrentUser.require().userId(), null, "subscription_plan",
                plan.getId().toString(), Map.of("code", code, "price", plan.getPrice().toPlainString()));
        return plans();
    }

    @Transactional
    public List<AdminPlan> updatePlan(UUID id, PlanRequest request) {
        SubscriptionPlan plan = plans.findById(id).orElseThrow(() -> ApiException.notFound("No existe ese plan."));
        if (request.lockVersion() != null && request.lockVersion() != plan.getLockVersion()) {
            throw ApiException.conflict("STALE_VERSION", "Otro administrador modificó este plan. Recargá para ver los cambios.");
        }
        if (plan.isFree() && request.price().signum() != 0) {
            throw ApiException.field("price", "El plan gratuito no puede tener precio.");
        }
        if (!plan.isFree() && request.price().signum() <= 0) {
            throw ApiException.field("price", "Un plan pago necesita un precio mayor a cero.");
        }
        if (plan.isFree() && !request.activeForNew()) {
            throw ApiException.field("activeForNew", "El plan gratuito no se puede desactivar: es el que rige sin suscripción.");
        }
        BigDecimal oldPrice = plan.getPrice();
        plan.setName(request.name().strip());
        plan.setPrice(scale(request.price()));
        apply(plan, request);
        plans.saveAndFlush(plan);
        audit.record(AuditAction.PLAN_UPDATED, CurrentUser.require().userId(), null, "subscription_plan", id.toString(),
                Map.of("code", plan.getCode(), "oldPrice", oldPrice.toPlainString(), "price", plan.getPrice().toPlainString(),
                        "maxQuotes", plan.getMaxMonthlyQuotes(), "maxCalculators", plan.getMaxPublishedCalculators(),
                        "activeForNew", plan.isActiveForNew()));
        return plans();
    }

    private void apply(SubscriptionPlan plan, PlanRequest r) {
        plan.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().strip());
        plan.setMaxPublishedCalculators(r.maxPublishedCalculators());
        plan.setMaxMonthlyQuotes(r.maxMonthlyQuotes());
        plan.setCsvExport(r.csvExport());
        plan.setCustomBranding(r.customBranding());
        plan.setLeadEmailNotifications(r.leadEmailNotifications());
        plan.setActiveForNew(r.activeForNew());
        plan.setSortOrder(r.sortOrder());
        plan.setUpdatedAt(clock.instant());
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ suscripciones y pagos

    @Transactional(readOnly = true)
    public PageResponse<AdminSubscription> subscriptions(String status, int page, int size) {
        EnumSet<TenantSubscription.Status> statuses = status == null || status.isBlank()
                ? EnumSet.allOf(TenantSubscription.Status.class)
                : EnumSet.of(parse(TenantSubscription.Status.class, status));
        var result = subscriptions.findByStatusIn(statuses,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, String> names = businessNames(result.getContent().stream().map(TenantSubscription::getTenantId).toList());
        return PageResponse.of(result, s -> new AdminSubscription(s.getId(), s.getTenantId(), names.get(s.getTenantId()),
                s.getPlanName(), s.getStatus().name(), s.getProvider().name(), s.getExternalId(), s.getProviderStatus(),
                s.getPrice(), s.getCurrentPeriodEnd(), s.getGraceUntil(), s.getLastPaymentAt(), s.getLastSyncedAt(),
                s.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminPayment> payments(String status, int page, int size) {
        EnumSet<PaymentTransaction.Status> statuses = status == null || status.isBlank()
                ? EnumSet.allOf(PaymentTransaction.Status.class)
                : EnumSet.of(parse(PaymentTransaction.Status.class, status));
        var result = payments.findByStatusIn(statuses,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, String> names = businessNames(result.getContent().stream().map(PaymentTransaction::getTenantId).toList());
        return PageResponse.of(result, p -> new AdminPayment(p.getId(), p.getTenantId(), names.get(p.getTenantId()),
                p.getStatus().name(), p.getProviderStatus(), p.getAmount(), p.getCurrency(), p.getExternalId(),
                p.getChargeDate(), p.getPaidAt(), p.getCreatedAt()));
    }

    // ------------------------------------------------------------------ integración y conciliación

    @Transactional(readOnly = true)
    public IntegrationHealth health() {
        Instant now = clock.instant();
        Instant lastNotification = jdbc.query("""
                SELECT max(received_at) FROM payment_provider_events
                WHERE event_type NOT IN ('reconciliation', 'reconciliation_run')""",
                (rs, i) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant()).getFirst();
        Long failed = jdbc.queryForObject("SELECT count(*) FROM payment_provider_events WHERE result = 'FAILED' AND received_at >= ?",
                Long.class, Timestamp.from(now.minus(Duration.ofDays(7))));
        List<ProviderEvent> lastRun = events("event_type = 'reconciliation_run'", 1);
        return new IntegrationHealth(provider.name(), provider.configured(),
                "FAKE".equals(provider.name()) || !properties.getPayments().getMercadopago().getWebhookSecret().isBlank(),
                lastNotification, failed == null ? 0 : failed,
                lastRun.isEmpty() ? null : lastRun.getFirst().receivedAt(),
                lastRun.isEmpty() ? null : lastRun.getFirst().detail(),
                events("result = 'CORRECTED'", 20), events("result = 'FAILED'", 20));
    }

    private List<ProviderEvent> events(String where, int limit) {
        return jdbc.query("SELECT event_type, resource_id, result, detail, received_at FROM payment_provider_events WHERE "
                        + where + " ORDER BY received_at DESC LIMIT " + limit,
                (rs, i) -> new ProviderEvent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()));
    }

    public BillingJobs.Report reconcileNow() {
        if (!provider.configured()) {
            throw ApiException.conflict("PAYMENTS_UNAVAILABLE", "El proveedor de pagos no está configurado.");
        }
        BillingJobs.Report report = jobs.run();
        audit.record(AuditAction.BILLING_RECONCILED, CurrentUser.require().userId(), null, "billing", null,
                Map.of("checked", report.checked(), "corrected", report.corrected(), "expired", report.expired(),
                        "failed", report.failed()));
        return report;
    }

    // ------------------------------------------------------------------ métricas

    @Transactional(readOnly = true)
    public RevenueMetrics revenue() {
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays(30)));
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(DISTINCT tenant_id) FROM tenant_subscriptions
                    WHERE (status IN ('ACTIVE','TRIALING') AND (current_period_end IS NULL OR current_period_end + interval '3 days' > ?))
                       OR (status = 'PAST_DUE' AND grace_until > ?)
                       OR (status = 'CANCELED' AND current_period_end > ?)) AS paid,
                  (SELECT count(*) FROM tenant_subscriptions WHERE status = 'CANCELED' AND canceled_at >= ?
                     AND coalesce(end_reason, '') <> 'REPLACED') AS canceled,
                  (SELECT count(*) FROM tenant_subscriptions WHERE status = 'PAST_DUE') AS past_due,
                  (SELECT coalesce(sum(CASE WHEN billing_period = 'YEARLY' THEN price / 12 ELSE price END), 0)
                     FROM tenant_subscriptions
                    WHERE (status IN ('ACTIVE','TRIALING') AND (current_period_end IS NULL OR current_period_end + interval '3 days' > ?))
                       OR (status = 'PAST_DUE' AND grace_until > ?)) AS mrr,
                  (SELECT coalesce(sum(amount), 0) FROM payment_transactions WHERE status = 'APPROVED' AND paid_at >= ?) AS collected,
                  (SELECT count(*) FROM payment_transactions WHERE status = 'REJECTED' AND created_at >= ?) AS failed
                """, (rs, i) -> new RevenueMetrics(rs.getLong("paid"), rs.getLong("canceled"), rs.getLong("past_due"),
                        rs.getBigDecimal("mrr").setScale(2, RoundingMode.HALF_UP),
                        rs.getBigDecimal("collected").setScale(2, RoundingMode.HALF_UP), rs.getLong("failed")),
                now, now, now, since, now, now, since, since);
    }

    private Map<UUID, String> businessNames(List<UUID> tenantIds) {
        Map<UUID, String> names = new java.util.HashMap<>();
        if (tenantIds.isEmpty()) {
            return names;
        }
        List<UUID> distinct = tenantIds.stream().distinct().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(distinct.size(), "?"));
        jdbc.query("SELECT tenant_id, business_name FROM business_profiles WHERE tenant_id IN (" + placeholders + ")",
                rs -> {
                    names.put(rs.getObject(1, UUID.class), rs.getString(2));
                }, distinct.toArray());
        return names;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.field("status", "Estado desconocido.");
        }
    }
}
