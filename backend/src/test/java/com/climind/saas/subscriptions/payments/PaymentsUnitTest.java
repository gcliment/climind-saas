package com.climind.saas.subscriptions.payments;

import com.climind.saas.shared.config.AppProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaymentsUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-07T15:00:00Z");
    private static final String SECRET = "clave-secreta-de-webhooks";

    /** HMAC calculado de forma independiente (no reutiliza el código bajo prueba). */
    private static String hmac(String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    @Nested
    class FirmaDeWebhooks {

        private final Duration tolerance = Duration.ofMinutes(15);

        @Test
        void aceptaLaFirmaSegunElManifestDeLaDocumentacion() throws Exception {
            String ts = String.valueOf(NOW.getEpochSecond());
            String v1 = hmac("id:123456;request-id:abc-123;ts:" + ts + ";");
            assertThat(MercadoPagoSignature.verify("ts=" + ts + ",v1=" + v1, "abc-123", "123456", SECRET, NOW, tolerance)).isTrue();
            // Con espacios y en otro orden.
            assertThat(MercadoPagoSignature.verify(" v1=" + v1 + " , ts=" + ts, "abc-123", "123456", SECRET, NOW, tolerance)).isTrue();
        }

        @Test
        void dataIdAlfanumericoVaEnMinusculasYLasPartesFaltantesSeOmiten() throws Exception {
            String ts = String.valueOf(NOW.toEpochMilli()); // también en milisegundos
            String v1 = hmac("id:ord01jq4s4ky8;ts:" + ts + ";");
            assertThat(MercadoPagoSignature.verify("ts=" + ts + ",v1=" + v1, null, "ORD01JQ4S4KY8", SECRET, NOW, tolerance)).isTrue();
        }

        @Test
        void rechazaFirmasAlteradasViejasOIncompletas() throws Exception {
            String ts = String.valueOf(NOW.getEpochSecond());
            String v1 = hmac("id:123456;request-id:abc-123;ts:" + ts + ";");
            assertThat(MercadoPagoSignature.verify("ts=" + ts + ",v1=" + v1, "abc-123", "999999", SECRET, NOW, tolerance)).isFalse();
            assertThat(MercadoPagoSignature.verify("ts=" + ts + ",v1=" + v1, "abc-123", "123456", "otra", NOW, tolerance)).isFalse();
            assertThat(MercadoPagoSignature.verify("ts=" + ts + ",v1=" + v1, "abc-123", "123456", SECRET,
                    NOW.plus(Duration.ofHours(1)), tolerance)).isFalse();
            assertThat(MercadoPagoSignature.verify("v1=" + v1, "abc-123", "123456", SECRET, NOW, tolerance)).isFalse();
            assertThat(MercadoPagoSignature.verify(null, "abc-123", "123456", SECRET, NOW, tolerance)).isFalse();
            // Sin clave configurada nunca se acepta nada.
            assertThat(MercadoPagoSignature.verify("ts=" + ts + ",v1=" + v1, "abc-123", "123456", "", NOW, tolerance)).isFalse();
        }
    }

    @Nested
    class ClienteDeMercadoPago {

        private AppProperties properties(String token) {
            AppProperties p = new AppProperties();
            p.getPayments().getMercadopago().setAccessToken(token);
            p.getPayments().getMercadopago().setApiUrl("https://api.mercadopago.test");
            return p;
        }

        @Test
        void creaLaSuscripcionConPagoPendienteYDevuelveElLinkDePago() {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.mercadopago.test/preapproval"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer TEST-token"))
                    .andExpect(header("X-Idempotency-Key", "ref-1"))
                    .andExpect(content().json("""
                            {"reason":"Climind Cotiza - Plan Pro","external_reference":"ref-1","payer_email":"ana@ejemplo.com",
                             "auto_recurring":{"frequency":1,"frequency_type":"months","transaction_amount":9900,"currency_id":"ARS"},
                             "back_url":"https://cotiza.test/app/plan?retorno=1","status":"pending"}"""))
                    .andRespond(withSuccess("""
                            {"id":"2c93808479","status":"pending","init_point":"https://www.mercadopago.com.ar/subscriptions/checkout?preapproval_id=2c93808479"}""",
                            MediaType.APPLICATION_JSON));
            MercadoPagoProvider mp = new MercadoPagoProvider(builder, properties("TEST-token"), Clock.fixed(NOW, ZoneOffset.UTC));

            PaymentProvider.Checkout checkout = mp.createSubscription(new PaymentProvider.CheckoutRequest("ref-1",
                    "Climind Cotiza - Plan Pro", new BigDecimal("9900"), "ARS", "ana@ejemplo.com",
                    "https://cotiza.test/app/plan?retorno=1"));
            assertThat(checkout.externalId()).isEqualTo("2c93808479");
            assertThat(checkout.checkoutUrl()).startsWith("https://www.mercadopago.com.ar/subscriptions/checkout");
            server.verify();
        }

        @Test
        void enModoPruebaElPagadorEsLaCuentaDePrueba() {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.mercadopago.test/preapproval"))
                    .andExpect(content().json("""
                            {"payer_email":"test_user_123@testuser.com"}"""))
                    .andRespond(withSuccess("""
                            {"id":"2c93808480","status":"pending","init_point":"https://www.mercadopago.com.ar/subscriptions/checkout?preapproval_id=2c93808480"}""",
                            MediaType.APPLICATION_JSON));
            AppProperties props = properties("TEST-token");
            props.getPayments().getMercadopago().setTestPayerEmail(" test_user_123@testuser.com ");
            MercadoPagoProvider mp = new MercadoPagoProvider(builder, props, Clock.fixed(NOW, ZoneOffset.UTC));
            assertThat(mp.testPayerMode()).isTrue();

            mp.createSubscription(new PaymentProvider.CheckoutRequest("ref-2", "Climind Cotiza - Plan Pro",
                    new BigDecimal("9900"), "ARS", "ana@ejemplo.com", "https://cotiza.test/app/plan?retorno=1"));
            server.verify();
        }

        @Test
        void buscaLasCuotasPaginandoSinLimit() {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            String cuota = """
                    {"id":%d,"preapproval_id":"2c93808479","status":"processed","transaction_amount":9900.00,
                     "currency_id":"ARS","debit_date":"2026-10-07T12:00:00.000-03:00","payment":{"id":%d,"status":"approved"}}""";
            server.expect(requestTo("https://api.mercadopago.test/authorized_payments/search?preapproval_id=2c93808479&offset=0"))
                    .andRespond(withSuccess("{\"paging\":{\"total\":3},\"results\":[" + cuota.formatted(1, 11) + ","
                            + cuota.formatted(2, 12) + "]}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://api.mercadopago.test/authorized_payments/search?preapproval_id=2c93808479&offset=2"))
                    .andRespond(withSuccess("{\"paging\":{\"total\":3},\"results\":[" + cuota.formatted(3, 13) + "]}",
                            MediaType.APPLICATION_JSON));
            MercadoPagoProvider mp = new MercadoPagoProvider(builder, properties("TEST-token"), Clock.fixed(NOW, ZoneOffset.UTC));

            assertThat(mp.searchPayments("2c93808479")).hasSize(3);
            server.verify();
        }

        @Test
        void leeCuotasConSuPagoYSuscripciones() {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.mercadopago.test/authorized_payments/6114264375"))
                    .andRespond(withSuccess("""
                            {"id":6114264375,"preapproval_id":"2c93808479","status":"processed","transaction_amount":9900.00,
                             "currency_id":"ARS","debit_date":"2026-10-07T12:00:00.000-03:00",
                             "last_modified":"2026-10-07T12:05:00.000-03:00","payment":{"id":555,"status":"approved"}}""",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://api.mercadopago.test/preapproval/2c93808479"))
                    .andRespond(withSuccess("""
                            {"id":"2c93808479","external_reference":"ref-1","status":"authorized",
                             "auto_recurring":{"transaction_amount":9900}}""", MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://api.mercadopago.test/preapproval/no-existe"))
                    .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));
            MercadoPagoProvider mp = new MercadoPagoProvider(builder, properties("TEST-token"), Clock.fixed(NOW, ZoneOffset.UTC));

            PaymentProvider.RemotePayment payment = mp.fetchPayment("6114264375").orElseThrow();
            assertThat(payment.subscriptionExternalId()).isEqualTo("2c93808479");
            assertThat(payment.paymentStatus()).isEqualTo("approved");
            assertThat(payment.chargeStatus()).isEqualTo("processed");
            assertThat(payment.amount()).isEqualByComparingTo("9900");
            assertThat(payment.chargeDate()).isEqualTo(Instant.parse("2026-10-07T15:00:00Z"));
            assertThat(mp.fetchSubscription("2c93808479").orElseThrow().status()).isEqualTo("authorized");
            assertThat(mp.fetchSubscription("no-existe")).isEmpty();
            server.verify();
        }

        @Test
        void erroresDelProveedorYFaltaDeCredenciales() {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://api.mercadopago.test/preapproval/x")).andRespond(withServerError());
            MercadoPagoProvider mp = new MercadoPagoProvider(builder, properties("TEST-token"), Clock.fixed(NOW, ZoneOffset.UTC));
            assertThatThrownBy(() -> mp.fetchSubscription("x")).isInstanceOf(PaymentProviderException.class)
                    .hasMessageNotContaining("TEST-token");

            MercadoPagoProvider unconfigured = new MercadoPagoProvider(RestClient.builder(), properties(""),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            assertThat(unconfigured.configured()).isFalse();
            assertThatThrownBy(() -> unconfigured.fetchSubscription("x")).isInstanceOf(PaymentProviderException.class);
        }
    }

    @Nested
    class SimuladorSoloFueraDeProduccion {

        private final PaymentsConfig config = new PaymentsConfig();

        @SuppressWarnings("unchecked")
        private ObjectProvider<FakePaymentProvider> provider(FakePaymentProvider fake) {
            ObjectProvider<FakePaymentProvider> p = org.mockito.Mockito.mock(ObjectProvider.class);
            org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(fake);
            return p;
        }

        @Test
        void enProduccionElSimuladorImpideArrancar() {
            AppProperties properties = new AppProperties();
            properties.getPayments().setProvider("fake");
            MockEnvironment prod = new MockEnvironment();
            prod.setActiveProfiles("prod");
            FakePaymentProvider fake = new FakePaymentProvider("https://cotiza.test", Clock.systemUTC());
            assertThatThrownBy(() -> config.paymentProvider(properties, Clock.systemUTC(), prod, provider(fake)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("prohibido en producción");
        }

        @Test
        void enDesarrolloSeUsaElSimuladorYEnProduccionMercadoPago() {
            AppProperties properties = new AppProperties();
            properties.getPayments().setProvider("fake");
            MockEnvironment dev = new MockEnvironment();
            dev.setActiveProfiles("dev");
            FakePaymentProvider fake = new FakePaymentProvider("https://cotiza.test", Clock.systemUTC());
            assertThat(config.paymentProvider(properties, Clock.systemUTC(), dev, provider(fake))).isSameAs(fake);
            // Sin el bean del simulador (perfil prod) tampoco puede usarse.
            assertThatThrownBy(() -> config.paymentProvider(properties, Clock.systemUTC(), dev, provider(null)))
                    .isInstanceOf(IllegalStateException.class);

            properties.getPayments().setProvider("mercadopago");
            MockEnvironment prod = new MockEnvironment();
            prod.setActiveProfiles("prod");
            assertThat(config.paymentProvider(properties, Clock.systemUTC(), prod, provider(null)).name()).isEqualTo("MERCADOPAGO");
        }
    }
}
