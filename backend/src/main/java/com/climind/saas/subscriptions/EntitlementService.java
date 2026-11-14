package com.climind.saas.subscriptions;

import com.climind.saas.tenants.Tenant;
import com.climind.saas.tenants.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Plan vigente de un negocio. Regla: si alguna suscripción paga da beneficios en este momento
 * ({@link TenantSubscription#grantsBenefits}), rigen los valores CONTRATADOS en esa suscripción; si no, rige
 * el plan gratuito con sus valores actuales. Nada de esto se decide en el frontend.
 */
@Service
public class EntitlementService {

    private static final EnumSet<TenantSubscription.Status> WITH_BENEFITS = EnumSet.of(
            TenantSubscription.Status.ACTIVE, TenantSubscription.Status.TRIALING,
            TenantSubscription.Status.PAST_DUE, TenantSubscription.Status.CANCELED);

    private final TenantSubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final TenantService tenants;
    private final Clock clock;

    public EntitlementService(TenantSubscriptionRepository subscriptions, SubscriptionPlanRepository plans,
                              TenantService tenants, Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.tenants = tenants;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Entitlements forTenant(UUID tenantId) {
        Instant now = clock.instant();
        ZoneId zone = tenants.find(tenantId).map(Tenant::getTimezone).map(ZoneId::of)
                .orElse(ZoneId.of("America/Argentina/Buenos_Aires"));
        return subscriptions.findByTenantIdAndStatusIn(tenantId, WITH_BENEFITS).stream()
                .filter(s -> s.grantsBenefits(now))
                .max(Comparator.comparing(s -> s.getCurrentPeriodEnd() == null ? now : s.getCurrentPeriodEnd()))
                .map(s -> paid(s, zone, now))
                .orElseGet(() -> free(zone, now));
    }

    private Entitlements paid(TenantSubscription s, ZoneId zone, Instant now) {
        LocalDate start = s.getCurrentPeriodStart() == null ? LocalDate.ofInstant(now, zone)
                : LocalDate.ofInstant(s.getCurrentPeriodStart(), zone);
        LocalDate end = s.getCurrentPeriodEnd() == null ? start.plusMonths(1)
                : LocalDate.ofInstant(s.getCurrentPeriodEnd(), zone);
        // Si el período pagado venció y la renovación está en curso, el consumo sigue contando por meses.
        LocalDate today = LocalDate.ofInstant(now, zone);
        while (!today.isBefore(end)) {
            start = end;
            end = end.plusMonths(1);
        }
        return new Entitlements(s.getPlanCode(), s.getPlanName(), true, s.getMaxPublishedCalculators(),
                s.getMaxMonthlyQuotes(), s.isCsvExport(), s.isCustomBranding(), s.isLeadEmailNotifications(), start,
                end, s.getId());
    }

    private Entitlements free(ZoneId zone, Instant now) {
        SubscriptionPlan plan = plans.findByFreeTrue()
                .orElseThrow(() -> new IllegalStateException("No hay un plan gratuito configurado"));
        LocalDate start = LocalDate.ofInstant(now, zone).withDayOfMonth(1);
        return new Entitlements(plan.getCode(), plan.getName(), false, plan.getMaxPublishedCalculators(),
                plan.getMaxMonthlyQuotes(), plan.isCsvExport(), plan.isCustomBranding(),
                plan.isLeadEmailNotifications(), start, start.plusMonths(1), null);
    }
}
