package com.climind.saas.subscriptions;

import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Escenarios 7, 8 y 9 del MVP: límites aplicados en el backend, contratación con Mercado Pago (simulado) que
 * habilita beneficios SOLO con un pago confirmado servidor a servidor, y pagos rechazados o cancelaciones sin
 * pérdida de datos. Cubre los eventos exigidos: pago aprobado, pendiente y rechazado, webhook duplicado y fuera
 * de orden, suscripción cancelada y conciliación.
 */
class BillingIT extends IntegrationTest {

    private static final String ANSWERS = """
            {"answers":{"tipo":"basica","m2":%d,"ambientes":1,"frecuencia":"unica"}}""";

    @Autowired
    private UsageService usage;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private BillingJobs jobs;

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String plan(Browser owner) throws Exception {
        return JsonPath.read(body(owner.get("/api/v1/billing")), "$.current.code");
    }

    /** Inicia la contratación y devuelve el id de la suscripción en el "proveedor". */
    private String checkout(Browser owner, String planCode) throws Exception {
        MvcResult result = owner.post("/api/v1/billing/checkout", "{\"planCode\":\"" + planCode + "\"}");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        String url = JsonPath.read(body(result), "$.checkoutUrl");
        assertThat(url).contains("/app/plan/simulador?sub=fake-sub-");
        return url.substring(url.indexOf("sub=") + 4);
    }

    private MvcResult webhook(String type, String dataId, String notificationId) throws Exception {
        String requestId = UUID.randomUUID().toString();
        return mockMvc.perform(post("/api/v1/webhooks/mercadopago")
                .queryParam("data.id", dataId).queryParam("type", type)
                .header("x-signature", payments.sign(dataId, requestId, clock.instant()))
                .header("x-request-id", requestId)
                .contentType("application/json")
                .content("{\"id\":\"" + notificationId + "\",\"type\":\"" + type + "\",\"action\":\"updated\","
                        + "\"live_mode\":false,\"data\":{\"id\":\"" + dataId + "\"}}")).andReturn();
    }

    private String notifyPayment(String paymentId) throws Exception {
        MvcResult result = webhook("subscription_authorized_payment", paymentId, UUID.randomUUID().toString());
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return JsonPath.read(body(result), "$.result");
    }

    private String status(String externalId) {
        return jdbc.queryForObject("select status from tenant_subscriptions where external_id = ?", String.class, externalId);
    }

    private Published publish(Browser owner, String name) throws Exception {
        return publishTemplate(owner, "Limpiezas Ana", name, "limpieza");
    }

    // ------------------------------------------------------------------ plan gratuito y límites

    @Test
    void planGratuitoPorDefecto() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String overview = body(ana.get("/api/v1/billing"));
        assertThat((String) JsonPath.read(overview, "$.current.code")).isEqualTo("GRATIS");
        assertThat((Boolean) JsonPath.read(overview, "$.paid")).isFalse();
        assertThat((Integer) JsonPath.read(overview, "$.usage.quotesLimit")).isEqualTo(30);
        assertThat((Integer) JsonPath.read(overview, "$.usage.publishedLimit")).isEqualTo(1);
        assertThat((String) JsonPath.read(overview, "$.usage.periodStart")).isEqualTo("2026-10-01");
        assertThat((List<String>) JsonPath.read(overview, "$.plans[*].code")).containsExactly("GRATIS", "PRO", "NEGOCIO");
        assertThat((Object) JsonPath.read(overview, "$.subscription")).isNull();

        // Catálogo público (página de precios): sin sesión.
        assertThat((List<Integer>) JsonPath.read(body(new Browser().get("/api/v1/catalog/plans")), "$[*].maxMonthlyQuotes"))
                .containsExactly(30, 300, 1500);
    }

    @Test
    void limiteDeCotizadoresPublicadosEnElBackend() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published first = publish(ana, "Hogares");
        String second = JsonPath.read(body(ana.post("/api/v1/calculators",
                "{\"name\":\"Oficinas\",\"templateCode\":\"limpieza\"}")), "$.id");

        MvcResult blocked = ana.post("/api/v1/calculators/" + second + "/publish", null);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(body(blocked), "$.code")).isEqualTo("PLAN_LIMIT_CALCULATORS");

        // Pausar uno libera el lugar; reactivarlo después ya no entra.
        ana.post("/api/v1/calculators/" + first.id() + "/pause", null);
        assertThat(ana.post("/api/v1/calculators/" + second + "/publish", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(ana.post("/api/v1/calculators/" + first.id() + "/resume", null).getResponse().getStatus()).isEqualTo(409);

        // Volver a publicar cambios de un cotizador ya publicado no cuenta como uno nuevo.
        assertThat(ana.post("/api/v1/calculators/" + second + "/publish", null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void cupoDeCotizacionesYAvisosDeUso() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publish(ana, "Hogares");
        jdbc.update("update subscription_plans set max_monthly_quotes = 5 where code = 'GRATIS'");
        emails().clear();

        Browser visitor = new Browser();
        for (int m2 = 40; m2 < 44; m2++) {
            assertThat(visitor.post(calc.api() + "/quotes", ANSWERS.formatted(m2)).getResponse().getStatus()).isEqualTo(201);
        }
        // 4 de 5 = 80 %: aviso.
        assertThat(emails().lastTo("ana@ejemplo.com").orElseThrow().subject()).contains("cerca del límite");
        // Respuestas inválidas no consumen.
        assertThat(visitor.post(calc.api() + "/quotes", "{\"answers\":{\"tipo\":\"basica\"}}").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(visitor.post(calc.api() + "/quotes", ANSWERS.formatted(50)).getResponse().getStatus()).isEqualTo(201);
        assertThat(emails().lastTo("ana@ejemplo.com").orElseThrow().subject()).contains("Alcanzaste el límite");

        // Sin cupo: no se guarda ni se muestra el formulario; recalcular algo ya cotizado sigue funcionando.
        MvcResult exceeded = visitor.post(calc.api() + "/quotes", ANSWERS.formatted(60));
        assertThat(exceeded.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(body(exceeded), "$.code")).isEqualTo("QUOTA_EXCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from quote_simulations", Long.class)).isEqualTo(5);
        assertThat((Boolean) JsonPath.read(body(visitor.get(calc.api())), "$.available")).isFalse();
        assertThat(visitor.post(calc.api() + "/quotes", ANSWERS.formatted(50)).getResponse().getStatus()).isEqualTo(201);
        assertThat((Integer) JsonPath.read(body(ana.get("/api/v1/billing")), "$.usage.quotesUsed")).isEqualTo(5);

        // El período siguiente arranca de cero.
        clock.advance(Duration.ofDays(25));
        assertThat(visitor.post(calc.api() + "/quotes", ANSWERS.formatted(70)).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void elCupoNoSeSuperaConPedidosSimultaneos() throws Exception {
        registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        UUID tenant = jdbc.queryForObject("select id from tenants", UUID.class);
        jdbc.update("update subscription_plans set max_monthly_quotes = 5 where code = 'GRATIS'");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            Callable<Boolean> consume = () -> transactions.execute(status -> usage.tryConsumeQuote(tenant));
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(consume));
            }
            long granted = 0;
            for (Future<Boolean> f : results) {
                granted += Boolean.TRUE.equals(f.get()) ? 1 : 0;
            }
            assertThat(granted).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count from usage_counters", Integer.class)).isEqualTo(5);
    }

    @Test
    void funcionesSegunElPlan() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publish(ana, "Hogares");
        String detail = body(ana.get("/api/v1/calculators/" + calc.id()));
        String config = JsonPath.parse((Object) JsonPath.read(detail, "$.config")).jsonString();
        ana.put("/api/v1/calculators/" + calc.id(), """
                {"name":"Hogares","slug":"%s","appearance":{"accentColor":"#7A1F5C","showLogo":true},"config":%s,"lockVersion":%d}"""
                .formatted(calc.slug(), config, (Integer) JsonPath.read(detail, "$.lockVersion")));
        ana.post("/api/v1/calculators/" + calc.id() + "/publish", null);

        // Gratis: identidad de Climind visible, color por defecto y sin CSV.
        String free = body(new Browser().get(calc.api()));
        assertThat((Boolean) JsonPath.read(free, "$.branding")).isTrue();
        assertThat((String) JsonPath.read(free, "$.accentColor")).isEqualTo("#0071DB");
        MvcResult csv = ana.get("/api/v1/leads/export.csv");
        assertThat(csv.getResponse().getStatus()).isEqualTo(403);
        assertThat((String) JsonPath.read(body(csv), "$.code")).isEqualTo("PLAN_FEATURE");

        grantPlan("ana@ejemplo.com", "PRO");
        String pro = body(new Browser().get(calc.api()));
        assertThat((Boolean) JsonPath.read(pro, "$.branding")).isFalse();
        assertThat((String) JsonPath.read(pro, "$.accentColor")).isEqualTo("#7A1F5C");
        assertThat(ana.get("/api/v1/leads/export.csv").getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ contratación y webhooks

    @Test
    void elPlanSeActivaSoloConUnPagoAprobadoConfirmadoPorElProveedor() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String sub = checkout(ana, "PRO");
        assertThat(status(sub)).isEqualTo("PENDING");
        String overview = body(ana.get("/api/v1/billing"));
        assertThat((String) JsonPath.read(overview, "$.current.code")).isEqualTo("GRATIS");
        assertThat((String) JsonPath.read(overview, "$.pendingCheckout.planCode")).isEqualTo("PRO");

        // Volver de la URL del checkout no activa nada.
        ana.get("/app/plan?retorno=1&status=approved&collection_status=approved&preapproval_id=" + sub);
        assertThat(plan(ana)).isEqualTo("GRATIS");

        // Medio de pago autorizado pero sin cobro aprobado todavía: sigue sin beneficios.
        payments.setStatus(sub, "authorized");
        assertThat(JsonPath.<String>read(body(webhook("subscription_preapproval", sub, "n-1")), "$.result")).isEqualTo("PROCESSED");
        assertThat(status(sub)).isEqualTo("PENDING");

        // Pago en proceso: tampoco.
        assertThat(notifyPayment(payments.charge(sub, "in_process").externalId())).isEqualTo("PROCESSED");
        assertThat(plan(ana)).isEqualTo("GRATIS");
        emails().clear();

        // Pago aprobado: plan activo, copia de los límites contratados, pago registrado y aviso.
        assertThat(notifyPayment(payments.charge(sub, "approved").externalId())).isEqualTo("PROCESSED");
        String active = body(ana.get("/api/v1/billing"));
        assertThat((String) JsonPath.read(active, "$.current.code")).isEqualTo("PRO");
        assertThat((Boolean) JsonPath.read(active, "$.paid")).isTrue();
        assertThat((Integer) JsonPath.read(active, "$.usage.publishedLimit")).isEqualTo(3);
        assertThat((String) JsonPath.read(active, "$.subscription.status")).isEqualTo("ACTIVE");
        assertThat((Object) JsonPath.read(active, "$.pendingCheckout")).isNull();
        assertThat((List<String>) JsonPath.read(active, "$.payments[*].status")).containsExactlyInAnyOrder("APPROVED", "PENDING");
        assertThat(emails().lastTo("ana@ejemplo.com").orElseThrow().subject()).contains("está activo");
    }

    @Test
    void webhooksDuplicadosYFirmasInvalidas() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String sub = checkout(ana, "PRO");
        String payment = payments.charge(sub, true).externalId();
        emails().clear();

        assertThat(JsonPath.<String>read(body(webhook("subscription_authorized_payment", payment, "notif-1")), "$.result"))
                .isEqualTo("PROCESSED");
        // Mercado Pago reintenta la misma notificación: no se procesa dos veces.
        assertThat(JsonPath.<String>read(body(webhook("subscription_authorized_payment", payment, "notif-1")), "$.result"))
                .isEqualTo("DUPLICATE");
        // Otra notificación del mismo pago: idempotente (un solo pago, un solo aviso).
        assertThat(notifyPayment(payment)).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions", Long.class)).isEqualTo(1);
        assertThat(emails().recent().stream().filter(m -> m.subject().contains("está activo")).count()).isEqualTo(1);

        // Firma inválida, ausente o vieja: 401 y nada cambia.
        String before = status(sub);
        MvcResult forged = mockMvc.perform(post("/api/v1/webhooks/mercadopago").queryParam("data.id", payment)
                .queryParam("type", "subscription_authorized_payment")
                .header("x-signature", "ts=" + clock.instant().getEpochSecond() + ",v1=" + "0".repeat(64))
                .header("x-request-id", "r").contentType("application/json").content("{}")).andReturn();
        assertThat(forged.getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post("/api/v1/webhooks/mercadopago").queryParam("data.id", payment)
                .contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        String requestId = UUID.randomUUID().toString();
        MvcResult replay = mockMvc.perform(post("/api/v1/webhooks/mercadopago").queryParam("data.id", payment)
                .queryParam("type", "subscription_authorized_payment")
                .header("x-signature", payments.sign(payment, requestId, clock.instant().minus(Duration.ofHours(2))))
                .header("x-request-id", requestId).contentType("application/json").content("{\"id\":\"old\"}")).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        assertThat(status(sub)).isEqualTo(before);
        // Tipos que no son de suscripciones se ignoran sin error.
        assertThat(JsonPath.<String>read(body(webhook("payment", "123", "n-x")), "$.result")).isEqualTo("IGNORED");
    }

    @Test
    void pagoRechazadoGraciaYVencimientoSinPerderDatos() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String sub = checkout(ana, "PRO");
        notifyPayment(payments.charge(sub, true).externalId());
        Published first = publish(ana, "Hogares");
        Published second = publish(ana, "Oficinas");
        assertThat((Boolean) JsonPath.read(body(new Browser().get(second.api())), "$.available")).isTrue();

        // Un mes después la renovación se rechaza: PAST_DUE, conserva el plan durante la gracia.
        clock.advance(Duration.ofDays(31));
        emails().clear();
        notifyPayment(payments.charge(sub, false).externalId());
        assertThat(status(sub)).isEqualTo("PAST_DUE");
        assertThat(plan(ana)).isEqualTo("PRO");
        assertThat(emails().lastTo("ana@ejemplo.com").orElseThrow().subject()).contains("No pudimos cobrar");

        // Vencida la gracia: EXPIRED, rige el plan Gratis. Nada se borra; el cotizador que excede queda no disponible.
        clock.advance(Duration.ofDays(8));
        jobs.run();
        assertThat(status(sub)).isEqualTo("EXPIRED");
        assertThat(plan(ana)).isEqualTo("GRATIS");
        assertThat(emails().lastTo("ana@ejemplo.com").orElseThrow().subject()).contains("venció");
        assertThat(jdbc.queryForObject("select count(*) from calculators where status = 'PUBLICADO'", Long.class)).isEqualTo(2);
        assertThat((Boolean) JsonPath.read(body(new Browser().get(first.api())), "$.available")).isTrue();
        assertThat((Boolean) JsonPath.read(body(new Browser().get(second.api())), "$.available")).isFalse();
        assertThat(payments.fetchSubscription(sub).orElseThrow().status()).isEqualTo("cancelled");
    }

    @Test
    void unRechazoViejoQueLlegaTardeNoPisaUnPagoPosterior() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String sub = checkout(ana, "PRO");
        notifyPayment(payments.charge(sub, true).externalId());

        // Renovación: primer intento rechazado (su notificación se demora) y reintento aprobado.
        clock.advance(Duration.ofDays(30));
        String rejected = payments.charge(sub, false).externalId();
        clock.advance(Duration.ofDays(2));
        notifyPayment(payments.charge(sub, true).externalId());
        String periodEnd = jdbc.queryForObject("select current_period_end::text from tenant_subscriptions", String.class);

        // Llega tarde la notificación del rechazo viejo: no pasa a PAST_DUE ni acorta el período.
        notifyPayment(rejected);
        assertThat(status(sub)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select current_period_end::text from tenant_subscriptions", String.class))
                .isEqualTo(periodEnd);
        assertThat(jdbc.queryForList("select status from payment_transactions order by created_at", String.class))
                .containsExactlyInAnyOrder("APPROVED", "REJECTED", "APPROVED");
    }

    @Test
    void cancelacionPorElNegocioYPorElProveedor() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String sub = checkout(ana, "PRO");
        notifyPayment(payments.charge(sub, true).externalId());

        String canceled = body(ana.post("/api/v1/billing/cancel", null));
        assertThat((String) JsonPath.read(canceled, "$.subscription.status")).isEqualTo("CANCELED");
        assertThat(payments.fetchSubscription(sub).orElseThrow().status()).isEqualTo("cancelled");
        // Conserva lo pagado hasta el fin del período; después, plan Gratis.
        assertThat(plan(ana)).isEqualTo("PRO");
        clock.advance(Duration.ofDays(31));
        assertThat(plan(ana)).isEqualTo("GRATIS");
        assertThat(ana.post("/api/v1/billing/cancel", null).getResponse().getStatus()).isEqualTo(409);

        // Baja desde Mercado Pago (o por 3 cobros rechazados): llega como notificación.
        String other = checkout(ana, "NEGOCIO");
        notifyPayment(payments.charge(other, true).externalId());
        payments.setStatus(other, "cancelled");
        webhook("subscription_preapproval", other, "cancel-1");
        assertThat(status(other)).isEqualTo("CANCELED");
    }

    @Test
    void cambioDePlanReemplazaLaSuscripcionAnterior() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String pro = checkout(ana, "PRO");
        notifyPayment(payments.charge(pro, true).externalId());
        assertThat(ana.post("/api/v1/billing/checkout", "{\"planCode\":\"PRO\"}").getResponse().getStatus()).isEqualTo(409);

        String negocio = checkout(ana, "NEGOCIO");
        assertThat(plan(ana)).isEqualTo("PRO"); // hasta que se apruebe el pago del plan nuevo
        notifyPayment(payments.charge(negocio, true).externalId());
        assertThat(plan(ana)).isEqualTo("NEGOCIO");
        assertThat(status(pro)).isEqualTo("CANCELED");
        assertThat(payments.fetchSubscription(pro).orElseThrow().status()).isEqualTo("cancelled");

        // Un checkout nuevo descarta el pendiente anterior (nunca dos pendientes).
        checkout(ana, "PRO");
        String again = checkout(ana, "PRO");
        assertThat(jdbc.queryForObject("select count(*) from tenant_subscriptions where status = 'PENDING'", Long.class))
                .isEqualTo(1);
        assertThat(status(again)).isEqualTo("PENDING");
    }

    @Test
    void laConciliacionCorrigeNotificacionesPerdidas() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String sub = checkout(ana, "PRO");
        payments.charge(sub, true); // la notificación nunca llega

        assertThat(plan(ana)).isEqualTo("GRATIS");
        Browser admin = loginAsSuperAdmin();
        MvcResult run = admin.post("/api/v1/admin/billing/reconcile", null);
        assertThat(run.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(run), "$.corrected")).isEqualTo(1);
        assertThat(plan(ana)).isEqualTo("PRO");

        String health = body(admin.get("/api/v1/admin/billing/health"));
        assertThat((String) JsonPath.read(health, "$.provider")).isEqualTo("FAKE");
        assertThat((List<String>) JsonPath.read(health, "$.recentCorrections[*].detail")).anyMatch(d -> d.contains("PENDING → ACTIVE"));
        assertThat((String) JsonPath.read(health, "$.lastReconciliationResult")).contains("corregidas 1");
        // Sin diferencias, no corrige nada.
        assertThat((Integer) JsonPath.read(body(admin.post("/api/v1/admin/billing/reconcile", null)), "$.corrected")).isZero();
    }

    @Test
    void checkoutAbandonadoSeDescarta() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String sub = checkout(ana, "PRO");
        clock.advance(Duration.ofHours(49));
        jobs.run();
        assertThat(status(sub)).isEqualTo("EXPIRED");
        assertThat((Object) JsonPath.read(body(ana.get("/api/v1/billing")), "$.pendingCheckout")).isNull();

        String other = checkout(ana, "PRO");
        assertThat(body(ana.post("/api/v1/billing/checkout/cancel", null))).contains("\"pendingCheckout\":null");
        assertThat(status(other)).isEqualTo("EXPIRED");
        assertThat(ana.post("/api/v1/billing/checkout", "{\"planCode\":\"GRATIS\"}").getResponse().getStatus()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ administración

    @Test
    void editarUnPlanNoCambiaContratosVigentes() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String sub = checkout(ana, "PRO");
        notifyPayment(payments.charge(sub, true).externalId());
        Browser admin = loginAsSuperAdmin();
        String plans = body(admin.get("/api/v1/admin/plans"));
        List<String> proIds = JsonPath.read(plans, "$[?(@.code == 'PRO')].id");
        String proId = proIds.getFirst();
        assertThat((List<Integer>) JsonPath.read(plans, "$[?(@.code == 'PRO')].activeSubscriptions")).containsExactly(1);

        MvcResult updated = admin.put("/api/v1/admin/plans/" + proId, """
                {"name":"Pro","price":12900,"maxPublishedCalculators":2,"maxMonthlyQuotes":200,"csvExport":true,
                 "customBranding":true,"leadEmailNotifications":true,"activeForNew":true,"sortOrder":2}""");
        assertThat(updated.getResponse().getStatus()).as(body(updated)).isEqualTo(200);

        // Ana conserva lo que contrató.
        String overview = body(ana.get("/api/v1/billing"));
        assertThat((Integer) JsonPath.read(overview, "$.usage.publishedLimit")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(overview, "$.usage.quotesLimit")).isEqualTo(300);
        assertThat(new BigDecimal(JsonPath.read(overview, "$.current.price").toString())).isEqualByComparingTo("9900");
        // Una contratación nueva usa los valores nuevos.
        Browser bruno = registerVerifiedAndLogin("Bruno", "bruno@ejemplo.com");
        String brunoSub = checkout(bruno, "PRO");
        assertThat(jdbc.queryForObject("select price::text from tenant_subscriptions where external_id = ?", String.class,
                brunoSub)).isEqualTo("12900.00");

        // Validaciones y planes nuevos.
        assertThat(admin.put("/api/v1/admin/plans/" + proId, """
                {"name":"Pro","price":0,"maxPublishedCalculators":2,"maxMonthlyQuotes":200,"activeForNew":true,"sortOrder":2}""")
                .getResponse().getStatus()).isEqualTo(400);
        MvcResult created = admin.post("/api/v1/admin/plans", """
                {"code":"agencia","name":"Agencia","price":49900,"maxPublishedCalculators":30,"maxMonthlyQuotes":6000,
                 "csvExport":true,"customBranding":true,"leadEmailNotifications":true,"activeForNew":false,"sortOrder":4}""");
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(200);
        assertThat((List<String>) JsonPath.read(body(created), "$[*].code")).contains("AGENCIA");
        // Desactivado para nuevas contrataciones: no aparece en el catálogo ni se puede contratar.
        assertThat(body(new Browser().get("/api/v1/catalog/plans"))).doesNotContain("AGENCIA");
        assertThat(ana.post("/api/v1/billing/checkout", "{\"planCode\":\"AGENCIA\"}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void panelDeAdministracionDeNegociosYMetricas() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publish(ana, "Hogares");
        String sub = checkout(ana, "PRO");
        notifyPayment(payments.charge(sub, true).externalId());
        Browser bruno = registerVerifiedAndLogin("Bruno", "bruno@ejemplo.com");
        new Browser().post(calc.api() + "/quotes", ANSWERS.formatted(40));

        // Un OWNER no accede a la administración.
        assertThat(ana.get("/api/v1/admin/tenants").getResponse().getStatus()).isEqualTo(403);
        assertThat(ana.get("/api/v1/admin/plans").getResponse().getStatus()).isEqualTo(403);

        Browser admin = loginAsSuperAdmin();
        String tenants = body(admin.get("/api/v1/admin/tenants?q=limpiezas"));
        assertThat((Integer) JsonPath.read(tenants, "$.totalItems")).isEqualTo(1);
        assertThat((String) JsonPath.read(tenants, "$.items[0].planCode")).isEqualTo("PRO");
        assertThat((Integer) JsonPath.read(tenants, "$.items[0].quotesUsed")).isEqualTo(1);
        assertThat((String) JsonPath.read(tenants, "$.items[0].ownerEmail")).isEqualTo("ana@ejemplo.com");
        assertThat((Integer) JsonPath.read(body(admin.get("/api/v1/admin/tenants")), "$.totalItems")).isEqualTo(2);

        // Ficha de cada negocio: contacto y hasta dónde llegó.
        String anaId = JsonPath.read(tenants, "$.items[0].id");
        String ficha = body(admin.get("/api/v1/admin/tenants/" + anaId));
        assertThat((String) JsonPath.read(ficha, "$.businessName")).isEqualTo("Limpiezas Ana");
        assertThat((String) JsonPath.read(ficha, "$.planName")).isEqualTo("Pro");
        assertThat((String) JsonPath.read(ficha, "$.contact.whatsapp")).isEqualTo("5491155550000");
        assertThat((String) JsonPath.read(ficha, "$.contact.industryLabel")).isEqualTo("Limpieza");
        assertThat((String) JsonPath.read(ficha, "$.owner.email")).isEqualTo("ana@ejemplo.com");
        assertThat((Boolean) JsonPath.read(ficha, "$.owner.emailVerified")).isTrue();
        assertThat((String) JsonPath.read(ficha, "$.owner.lastLoginAt")).isNotNull();
        assertThat((Integer) JsonPath.read(ficha, "$.calculators.total")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(ficha, "$.calculators.published")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(ficha, "$.activity.quotesTotal")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(ficha, "$.activity.leadsTotal")).isZero();

        // Recién registrado: sin datos del negocio ni cotizadores.
        String brunoTenant = jdbc.queryForObject("""
                select m.tenant_id::text from tenant_memberships m join users u on u.id = m.user_id where u.email = 'bruno@ejemplo.com'
                """, String.class);
        String nuevo = body(admin.get("/api/v1/admin/tenants/" + brunoTenant));
        assertThat((String) JsonPath.read(nuevo, "$.businessName")).isEqualTo("Negocio de Bruno");
        assertThat((Object) JsonPath.read(nuevo, "$.contact.whatsapp")).isNull();
        assertThat((Object) JsonPath.read(nuevo, "$.onboardingCompletedAt")).isNull();
        assertThat((Integer) JsonPath.read(nuevo, "$.calculators.total")).isZero();
        assertThat(admin.get("/api/v1/admin/tenants/" + java.util.UUID.randomUUID()).getResponse().getStatus()).isEqualTo(404);
        assertThat(ana.get("/api/v1/admin/tenants/" + anaId).getResponse().getStatus()).isEqualTo(403);

        String summary = body(admin.get("/api/v1/admin/summary"));
        assertThat((Integer) JsonPath.read(summary, "$.revenue.paidSubscribers")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(summary, "$.freeTenants")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(summary, "$.quotesThisMonth")).isEqualTo(1);
        assertThat(new BigDecimal(JsonPath.read(summary, "$.revenue.estimatedMrr").toString())).isEqualByComparingTo("9900");
        assertThat(new BigDecimal(JsonPath.read(summary, "$.revenue.collectedLast30Days").toString())).isEqualByComparingTo("9900");
        assertThat((Integer) JsonPath.read(body(admin.get("/api/v1/admin/subscriptions?status=ACTIVE")), "$.totalItems")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body(admin.get("/api/v1/admin/payments?status=APPROVED")), "$.totalItems")).isEqualTo(1);

        // Cancelado: el MRR estimado baja aunque lo cobrado en el mes no cambie.
        ana.post("/api/v1/billing/cancel", null);
        String after = body(admin.get("/api/v1/admin/summary"));
        assertThat(new BigDecimal(JsonPath.read(after, "$.revenue.estimatedMrr").toString())).isEqualByComparingTo("0");
        assertThat(new BigDecimal(JsonPath.read(after, "$.revenue.collectedLast30Days").toString())).isEqualByComparingTo("9900");

        // Suspender: el negocio no entra al panel y su cotizador deja de ser público. Reactivar lo devuelve.
        String brunoId = jdbc.queryForObject("""
                select m.tenant_id::text from tenant_memberships m join users u on u.id = m.user_id where u.email = 'bruno@ejemplo.com'
                """, String.class);
        assertThat(admin.post("/api/v1/admin/tenants/" + brunoId + "/suspend", "{\"reason\":\"\"}").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(admin.post("/api/v1/admin/tenants/" + brunoId + "/suspend", "{\"reason\":\"Uso abusivo\"}")
                .getResponse().getStatus()).isEqualTo(204);
        MvcResult blocked = bruno.get("/api/v1/business/profile");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
        assertThat((String) JsonPath.read(body(blocked), "$.code")).isEqualTo("TENANT_SUSPENDED");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'TENANT_SUSPENDED'", Long.class)).isEqualTo(1);
        assertThat(admin.post("/api/v1/admin/tenants/" + brunoId + "/reactivate", null).getResponse().getStatus()).isEqualTo(204);
        assertThat(bruno.get("/api/v1/business/profile").getResponse().getStatus()).isEqualTo(200);
    }
}
