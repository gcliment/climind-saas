package com.climind.saas.subscriptions.payments;

import com.climind.saas.shared.config.AppProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.Locale;

/**
 * Elige el proveedor de pagos. El simulador SOLO puede existir en dev y test: en producción
 * {@code PAYMENT_PROVIDER=fake} impide que la aplicación arranque (el simulador nunca puede activar planes
 * pagos reales).
 */
@Configuration
public class PaymentsConfig {

    @Bean
    @Profile({"dev", "test"})
    public FakePaymentProvider fakePaymentProvider(AppProperties properties, Clock clock) {
        return new FakePaymentProvider(properties.publicUrlWithoutTrailingSlash(), clock);
    }

    /** El proveedor que usa la aplicación (el simulador también es un PaymentProvider, de ahí @Primary). */
    @Bean
    @org.springframework.context.annotation.Primary
    public PaymentProvider paymentProvider(AppProperties properties, Clock clock, Environment environment,
                                           ObjectProvider<FakePaymentProvider> fake) {
        String provider = properties.getPayments().getProvider().trim().toLowerCase(Locale.ROOT);
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        return switch (provider) {
            case "fake" -> {
                if (prod) {
                    throw new IllegalStateException(
                            "PAYMENT_PROVIDER=fake está prohibido en producción: usá mercadopago.");
                }
                FakePaymentProvider simulator = fake.getIfAvailable();
                if (simulator == null) {
                    throw new IllegalStateException("El simulador de pagos solo existe en los perfiles dev y test.");
                }
                yield simulator;
            }
            case "mercadopago" -> {
                org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                        new org.springframework.http.client.SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(5_000);
                factory.setReadTimeout(15_000);
                yield new MercadoPagoProvider(RestClient.builder().requestFactory(factory), properties, clock);
            }
            default -> throw new IllegalStateException("PAYMENT_PROVIDER desconocido: " + provider);
        };
    }
}
