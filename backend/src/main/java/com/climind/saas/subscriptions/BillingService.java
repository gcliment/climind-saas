package com.climind.saas.subscriptions;

import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.security.CurrentUser;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.subscriptions.BillingDtos.BillingOverview;
import com.climind.saas.subscriptions.BillingDtos.PlanView;
import com.climind.saas.subscriptions.BillingDtos.SubscriptionView;
import com.climind.saas.subscriptions.payments.PaymentProvider;
import com.climind.saas.subscriptions.payments.PaymentProviderException;
import com.climind.saas.tenants.CurrentTenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan y suscripción del negocio autenticado: ver plan y consumo, contratar (checkout del proveedor), cancelar.
 * <p>
 * Volver de la URL de checkout NO activa nada: el plan se habilita cuando el proveedor confirma un pago
 * aprobado (webhook o conciliación). Cambiar de plan pago (subir o bajar) es una contratación nueva: al
 * aprobarse su primer pago, la suscripción anterior se da de baja.
 */
@Slf4j
@Service
public class BillingService {

    private static final EnumSet<TenantSubscription.Status> CURRENT = EnumSet.of(TenantSubscription.Status.ACTIVE,
            TenantSubscription.Status.PAST_DUE, TenantSubscription.Status.PAUSED, TenantSubscription.Status.TRIALING);

    private final SubscriptionPlanRepository plans;
    private final TenantSubscriptionRepository subscriptions;
    private final PaymentTransactionRepository payments;
    private final EntitlementService entitlements;
    private final UsageService usage;
    private final SubscriptionSync sync;
    private final PaymentProvider provider;
    private final BillingNotifier notifier;
    private final CurrentTenant currentTenant;
    private final AuditLogService audit;
    private final AppProperties properties;
    private final Clock clock;

    public BillingService(SubscriptionPlanRepository plans, TenantSubscriptionRepository subscriptions,
                          PaymentTransactionRepository payments, EntitlementService entitlements, UsageService usage,
                          SubscriptionSync sync, PaymentProvider provider, BillingNotifier notifier,
                          CurrentTenant currentTenant, AuditLogService audit, AppProperties properties, Clock clock) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.entitlements = entitlements;
        this.usage = usage;
        this.sync = sync;
        this.provider = provider;
        this.notifier = notifier;
        this.currentTenant = currentTenant;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Planes disponibles para contratar (catálogo público y página de planes). */
    @Transactional(readOnly = true)
    public List<PlanView> catalog() {
        return plans.findAllByOrderBySortOrderAscPriceAsc().stream()
                .filter(p -> p.isActiveForNew() || p.isFree()).map(PlanView::of).toList();
    }

    @Transactional(readOnly = true)
    public BillingOverview overview() {
        UUID tenantId = currentTenant.requireId();
        Entitlements e = entitlements.forTenant(tenantId);
        UsageService.Usage quotes = usage.quoteUsage(tenantId, e);
        List<TenantSubscription> all = subscriptions.findByTenantIdOrderByCreatedAtDesc(tenantId);
        TenantSubscription current = e.subscriptionId() == null ? null
                : all.stream().filter(s -> s.getId().equals(e.subscriptionId())).findFirst().orElse(null);
        TenantSubscription relevant = current != null ? current : all.stream()
                .filter(s -> s.getStatus() != TenantSubscription.Status.PENDING).findFirst().orElse(null);
        TenantSubscription pending = all.stream().filter(s -> s.getStatus() == TenantSubscription.Status.PENDING)
                .findFirst().orElse(null);
        Map<UUID, String> planNames = new java.util.HashMap<>();
        all.forEach(s -> planNames.put(s.getId(), s.getPlanName()));

        PlanView currentPlan = current != null
                ? new PlanView(e.planCode(), e.planName(), null, current.getPrice(), current.getCurrency(),
                current.getBillingPeriod().name(), false, e.maxPublishedCalculators(), e.maxMonthlyQuotes(),
                e.csvExport(), e.customBranding(), e.leadEmailNotifications())
                : plans.findByFreeTrue().map(PlanView::of).orElseThrow();
        return new BillingOverview(currentPlan, e.paid(),
                new BillingDtos.UsageView(quotes.used(), quotes.limit(), quotes.periodStart(), quotes.periodEnd(),
                        usage.publishedCalculators(tenantId), e.maxPublishedCalculators()),
                relevant == null ? null : SubscriptionView.of(relevant),
                pending == null ? null : SubscriptionView.of(pending),
                catalog(),
                payments.findTop24ByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                        .map(p -> new BillingDtos.PaymentView(p.getPaidAt() != null ? p.getPaidAt()
                                : p.getChargeDate() != null ? p.getChargeDate() : p.getCreatedAt(), p.getAmount(),
                                p.getCurrency(), p.getStatus().name(), planNames.get(p.getSubscriptionId())))
                        .toList(),
                provider.configured(), "FAKE".equals(provider.name()));
    }

    @Transactional
    public BillingDtos.CheckoutResponse checkout(BillingDtos.CheckoutRequest request) {
        UUID tenantId = currentTenant.requireId();
        SubscriptionPlan plan = plans.findByCode(request.planCode().strip().toUpperCase(java.util.Locale.ROOT))
                .filter(p -> !p.isFree() && p.isActiveForNew())
                .orElseThrow(() -> ApiException.field("planCode", "Ese plan no está disponible para contratar."));
        if (!provider.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_UNAVAILABLE",
                    "Los pagos todavía no están habilitados. Escribinos y te ayudamos a contratar.");
        }
        Instant now = clock.instant();
        List<TenantSubscription> current = subscriptions.findByTenantIdAndStatusIn(tenantId, CURRENT);
        if (current.stream().anyMatch(s -> s.getPlanCode().equals(plan.getCode())
                && s.getStatus() == TenantSubscription.Status.ACTIVE)) {
            throw ApiException.conflict("ALREADY_ON_PLAN", "Ya tenés el plan " + plan.getName() + ".");
        }
        // Un solo checkout pendiente: el anterior se descarta.
        subscriptions.findByTenantIdAndStatusIn(tenantId, EnumSet.of(TenantSubscription.Status.PENDING))
                .forEach(old -> {
                    sync.cancelAtProvider(old);
                    old.end("REPLACED_CHECKOUT", now);
                    subscriptions.saveAndFlush(old);
                });

        TenantSubscription sub = subscriptions.saveAndFlush(new TenantSubscription(tenantId, plan, sync.providerType(), now));
        PaymentProvider.Checkout checkout;
        try {
            checkout = provider.createSubscription(new PaymentProvider.CheckoutRequest(sub.getId().toString(),
                    "Climind Cotiza - Plan " + plan.getName(), plan.getPrice(), plan.getCurrency(),
                    CurrentUser.require().email(), properties.publicUrlWithoutTrailingSlash() + "/app/plan?retorno=1"));
        } catch (PaymentProviderException | org.springframework.web.client.RestClientException e) {
            log.warn("No se pudo iniciar el checkout del plan {}: {}", plan.getCode(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENTS_ERROR",
                    "No pudimos iniciar el pago con Mercado Pago. Probá de nuevo en unos minutos.");
        }
        sub.checkoutCreated(checkout.externalId(), checkout.checkoutUrl(), checkout.status(), now);
        subscriptions.save(sub);
        audit.record(AuditAction.SUBSCRIPTION_CHECKOUT_STARTED, CurrentUser.require().userId(), tenantId,
                "tenant_subscription", sub.getId().toString(), Map.of("plan", plan.getCode()));
        return new BillingDtos.CheckoutResponse(checkout.checkoutUrl());
    }

    /** Abandona una contratación pendiente. */
    @Transactional
    public BillingOverview cancelCheckout() {
        UUID tenantId = currentTenant.requireId();
        Instant now = clock.instant();
        subscriptions.findByTenantIdAndStatusIn(tenantId, EnumSet.of(TenantSubscription.Status.PENDING))
                .forEach(old -> {
                    sync.cancelAtProvider(old);
                    old.end("USER_ABANDONED", now);
                    subscriptions.saveAndFlush(old);
                });
        return overview();
    }

    /**
     * Cancela el plan pago: se da de baja en el proveedor (no hay más cobros) y conserva los beneficios hasta el
     * fin del período ya pagado. Después rige el plan gratuito. No se borra ningún dato.
     */
    @Transactional
    public BillingOverview cancel() {
        UUID tenantId = currentTenant.requireId();
        TenantSubscription sub = subscriptions.findByTenantIdAndStatusIn(tenantId, CURRENT).stream()
                .max(Comparator.comparing(TenantSubscription::getCreatedAt))
                .orElseThrow(() -> ApiException.conflict("NO_SUBSCRIPTION", "No tenés un plan pago para cancelar."));
        try {
            if (sub.getExternalId() != null) {
                provider.cancelSubscription(sub.getExternalId());
            }
        } catch (PaymentProviderException | org.springframework.web.client.RestClientException e) {
            log.warn("No se pudo cancelar en el proveedor la suscripción {}: {}", sub.getId(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENTS_ERROR",
                    "No pudimos cancelar en Mercado Pago. Probá de nuevo en unos minutos; no se te va a cobrar dos veces.");
        }
        sub.canceled("USER", clock.instant());
        subscriptions.saveAndFlush(sub);
        notifier.canceled(sub);
        audit.record(AuditAction.SUBSCRIPTION_CANCELED, CurrentUser.require().userId(), tenantId,
                "tenant_subscription", sub.getId().toString(), Map.of("plan", sub.getPlanCode()));
        return overview();
    }
}
