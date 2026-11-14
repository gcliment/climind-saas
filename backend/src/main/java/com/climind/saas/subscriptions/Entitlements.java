package com.climind.saas.subscriptions;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Lo que el plan vigente de un negocio le permite hoy. Lo resuelve {@link EntitlementService}: la
 * suscripción paga que da beneficios en este momento o, si no hay, el plan gratuito.
 *
 * @param periodStart inicio del período de consumo (fecha en la zona del negocio)
 * @param periodEnd   fin del período (exclusivo)
 * @param subscriptionId suscripción paga vigente (null en el plan gratuito)
 */
public record Entitlements(
        String planCode,
        String planName,
        boolean paid,
        int maxPublishedCalculators,
        int maxMonthlyQuotes,
        boolean csvExport,
        boolean customBranding,
        boolean leadEmailNotifications,
        LocalDate periodStart,
        LocalDate periodEnd,
        UUID subscriptionId) {
}
