package com.climind.saas.subscriptions;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** DTOs de planes y suscripciones (lado del negocio). Nunca exponen credenciales ni datos del proveedor. */
public final class BillingDtos {

    private BillingDtos() {
    }

    public record PlanView(String code, String name, String description, BigDecimal price, String currency,
                           String billingPeriod, boolean free, int maxPublishedCalculators, int maxMonthlyQuotes,
                           boolean csvExport, boolean customBranding, boolean leadEmailNotifications) {

        static PlanView of(SubscriptionPlan p) {
            return new PlanView(p.getCode(), p.getName(), p.getDescription(), p.getPrice(), p.getCurrency(),
                    p.getBillingPeriod().name(), p.isFree(), p.getMaxPublishedCalculators(), p.getMaxMonthlyQuotes(),
                    p.isCsvExport(), p.isCustomBranding(), p.isLeadEmailNotifications());
        }
    }

    public record UsageView(int quotesUsed, int quotesLimit, LocalDate periodStart, LocalDate periodEnd,
                            long publishedCalculators, int publishedLimit) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SubscriptionView(UUID id, String planCode, String planName, String status, BigDecimal price,
                                   String currency, Instant currentPeriodEnd, Instant graceUntil, Instant canceledAt,
                                   String checkoutUrl, Instant createdAt) {

        static SubscriptionView of(TenantSubscription s) {
            return new SubscriptionView(s.getId(), s.getPlanCode(), s.getPlanName(), s.getStatus().name(),
                    s.getPrice(), s.getCurrency(), s.getCurrentPeriodEnd(), s.getGraceUntil(), s.getCanceledAt(),
                    s.getCheckoutUrl(), s.getCreatedAt());
        }
    }

    public record PaymentView(Instant date, BigDecimal amount, String currency, String status, String planName) {
    }

    /**
     * @param current          plan vigente (lo contratado si es pago; el plan gratuito si no)
     * @param subscription     suscripción paga vigente o la última relevante (null si nunca contrató)
     * @param pendingCheckout  contratación iniciada y todavía sin pago aprobado
     * @param paymentsAvailable si se pueden contratar planes pagos (proveedor configurado)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BillingOverview(PlanView current, boolean paid, UsageView usage, SubscriptionView subscription,
                                  SubscriptionView pendingCheckout, List<PlanView> plans, List<PaymentView> payments,
                                  boolean paymentsAvailable, boolean simulator) {
    }

    public record CheckoutRequest(
            @NotBlank(message = "Elegí un plan.")
            @Size(max = 30)
            String planCode) {
    }

    public record CheckoutResponse(String checkoutUrl) {
    }
}
