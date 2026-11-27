package com.climind.saas.account;

import com.climind.saas.notifications.EmailMessage;
import com.climind.saas.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/** Tu cuenta: datos personales, contraseña, exportación de datos y baja de la cuenta. */
class AccountIT extends IntegrationTest {

    private static final String NEW_PASSWORD = "Otra-Clave-Segura-2026";
    private static final String QUOTE = """
            {"answers":{"tipo":"profunda","m2":60,"ambientes":3,"frecuencia":"unica"},
             "contact":{"name":"Carla Gómez","whatsapp":"11 2345-6789","email":"carla@ejemplo.com","consent":true}}""";

    @Autowired
    private AccountDeletionService deletions;

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ------------------------------------------------------------------ datos y contraseña

    @Test
    void elUsuarioEditaSuNombreYSusPreferenciasDeEmail() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        String account = body(ana.get("/api/v1/account"));
        assertThat((String) JsonPath.read(account, "$.email")).isEqualTo("ana@ejemplo.com");
        assertThat((Boolean) JsonPath.read(account, "$.owner")).isTrue();
        assertThat((Boolean) JsonPath.read(account, "$.productEmails")).isTrue();
        assertThat((Integer) JsonPath.read(account, "$.deletionGraceDays")).isEqualTo(30);

        MvcResult renamed = ana.put("/api/v1/account/profile", "{\"firstName\":\"Ana María\",\"lastName\":\"López\"}");
        assertThat(status(renamed)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(ana.get("/api/v1/auth/me")), "$.user.firstName")).isEqualTo("Ana María");
        assertThat(status(ana.put("/api/v1/account/profile", "{\"firstName\":\"\",\"lastName\":\"López\"}"))).isEqualTo(400);

        MvcResult prefs = ana.put("/api/v1/account/email-preferences", "{\"productEmails\":false}");
        assertThat((Boolean) JsonPath.read(body(prefs), "$.productEmails")).isFalse();
        assertThat(count("select count(*) from users where email = 'ana@ejemplo.com' and not product_emails")).isEqualTo(1);
    }

    @Test
    void cambiarLaContrasenaExigeLaActualYCierraLasOtrasSesiones() throws Exception {
        Browser laptop = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Browser phone = new Browser();
        assertThat(status(phone.post("/api/v1/auth/login", loginJson("ana@ejemplo.com", PASSWORD)))).isEqualTo(200);

        MvcResult wrong = laptop.post("/api/v1/account/password", """
                {"currentPassword":"no-es-esta","password":"%s","passwordConfirmation":"%s"}""".formatted(NEW_PASSWORD, NEW_PASSWORD));
        assertThat(status(wrong)).isEqualTo(400);
        assertThat(body(wrong)).contains("currentPassword");

        MvcResult weak = laptop.post("/api/v1/account/password", """
                {"currentPassword":"%s","password":"corta","passwordConfirmation":"corta"}""".formatted(PASSWORD));
        assertThat(status(weak)).isEqualTo(400);

        emails().clear();
        MvcResult ok = laptop.post("/api/v1/account/password", """
                {"currentPassword":"%s","password":"%s","passwordConfirmation":"%s"}""".formatted(PASSWORD, NEW_PASSWORD, NEW_PASSWORD));
        assertThat(status(ok)).as(body(ok)).isEqualTo(204);

        assertThat(status(laptop.get("/api/v1/auth/me"))).as("la sesión actual sigue").isEqualTo(200);
        assertThat(status(phone.get("/api/v1/auth/me"))).as("las otras sesiones se cierran").isEqualTo(401);
        assertThat(emails().lastTo("ana@ejemplo.com")).map(EmailMessage::template).contains("PASSWORD_CHANGED");
        assertThat(status(new Browser().post("/api/v1/auth/login", loginJson("ana@ejemplo.com", PASSWORD)))).isEqualTo(401);
        assertThat(status(new Browser().post("/api/v1/auth/login", loginJson("ana@ejemplo.com", NEW_PASSWORD)))).isEqualTo(200);
    }

    // ------------------------------------------------------------------ exportación

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void elDuenoDescargaTodosLosDatosDeSuNegocioYSoloLosSuyos() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published anaCalc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        MvcResult quote = new Browser().post(anaCalc.api() + "/quotes", QUOTE);
        assertThat(status(quote)).isEqualTo(201);
        String token = JsonPath.read(body(quote), "$.token");

        Browser beto = registerVerifiedAndLogin("Beto", "beto@ejemplo.com");
        Published betoCalc = publishTemplate(beto, "Catering Beto", "Eventos Beto", "catering");
        new Browser().post(betoCalc.api() + "/quotes", """
                {"answers":{"invitados":"40","menu":"clasico","bebidas":true,"mozos":false,"duracion":"4"},
                 "contact":{"name":"Cliente de Beto","whatsapp":"11 3333-4444","consent":true}}""");

        MvcResult export = ana.get("/api/v1/account/export");
        assertThat(status(export)).isEqualTo(200);
        assertThat(export.getResponse().getContentType()).isEqualTo("application/zip");
        assertThat(export.getResponse().getHeader("Content-Disposition")).contains("climind-cotiza-datos-2026-10-07.zip");
        Map<String, String> files = unzip(export.getResponse().getContentAsByteArray());
        assertThat(files).containsKeys("LEEME.txt", "negocio.json", "usuarios.json", "cotizadores.json",
                "cotizaciones.json", "consultas.json", "plan-y-pagos.json");
        assertThat(files.get("negocio.json")).contains("Limpiezas Ana");
        assertThat(files.get("cotizadores.json")).contains("Limpieza de hogares", "versiones_publicadas", "profunda");
        assertThat(files.get("cotizaciones.json")).contains("99000");
        assertThat(files.get("consultas.json")).contains("Carla Gómez", "carla@ejemplo.com", "NUEVA");
        assertThat(files.get("usuarios.json")).contains("ana@ejemplo.com");

        String all = String.join("\n", files.values());
        assertThat(all).as("nada de otro negocio").doesNotContain("Beto", "Catering", "Cliente de Beto");
        assertThat(all).as("sin secretos").doesNotContain(token, "password", "access_token", "visitor_hash",
                "token_hash");
        assertThat(count("select count(*) from audit_logs where action = 'ACCOUNT_DATA_EXPORTED'")).isEqualTo(1);
    }

    @Test
    void sinNegocioNoHayExportacionNiBaja() throws Exception {
        Browser admin = loginAsSuperAdmin();
        assertThat(status(admin.get("/api/v1/account"))).isEqualTo(200);
        assertThat(status(admin.get("/api/v1/account/export"))).isEqualTo(403);
        assertThat(status(admin.post("/api/v1/account/deletion",
                "{\"password\":\"%s\",\"confirmation\":\"ELIMINAR\"}".formatted(PASSWORD)))).isEqualTo(403);
        assertThat(status(new Browser().get("/api/v1/account"))).isEqualTo(401);
    }

    // ------------------------------------------------------------------ baja

    private MvcResult requestDeletion(Browser owner, String password, String confirmation) throws Exception {
        return owner.post("/api/v1/account/deletion",
                "{\"password\":\"%s\",\"confirmation\":\"%s\"}".formatted(password, confirmation));
    }

    @Test
    void laBajaPideConfirmacionCancelaElPlanYSePuedeDeshacer() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        grantPlan("ana@ejemplo.com", "PRO");

        assertThat(body(requestDeletion(ana, "no-es-esta", "ELIMINAR"))).contains("\"field\":\"password\"");
        assertThat(body(requestDeletion(ana, PASSWORD, "borrar"))).contains("\"field\":\"confirmation\"");
        assertThat(count("select count(*) from tenants where deletion_requested_at is not null")).isZero();

        emails().clear();
        MvcResult scheduled = requestDeletion(ana, PASSWORD, "eliminar");
        assertThat(status(scheduled)).as(body(scheduled)).isEqualTo(200);
        assertThat(Instant.parse(JsonPath.read(body(scheduled), "$.scheduledFor")))
                .isEqualTo(DEFAULT_NOW.plus(Duration.ofDays(30)));
        assertThat((Boolean) JsonPath.read(body(scheduled), "$.canceledPaidPlan")).isTrue();
        assertThat(jdbc.queryForObject("select status from tenant_subscriptions", String.class)).isEqualTo("CANCELED");
        assertThat(emails().lastTo("ana@ejemplo.com")).map(EmailMessage::template).contains("ACCOUNT_DELETION_SCHEDULED");
        assertThat(emails().lastTo("ana@ejemplo.com").orElseThrow().html()).contains("6 de noviembre de 2026");

        // Los cotizadores dejan de atender al público; el panel sigue accesible para exportar o arrepentirse.
        assertThat(status(new Browser().get(calc.api()))).isEqualTo(404);
        assertThat((String) JsonPath.read(body(ana.get("/api/v1/auth/me")), "$.tenant.deletionScheduledFor")).isNotNull();
        assertThat(status(ana.get("/api/v1/account/export"))).isEqualTo(200);

        MvcResult canceled = ana.send(delete("/api/v1/account/deletion"), null);
        assertThat(status(canceled)).isEqualTo(200);
        assertThat((Object) JsonPath.read(body(canceled), "$.deletionScheduledFor")).isNull();
        assertThat(status(new Browser().get(calc.api()))).isEqualTo(200);
        assertThat(status(ana.send(delete("/api/v1/account/deletion"), null))).isEqualTo(409);

        clock.advance(Duration.ofDays(31));
        assertThat(deletions.runDue()).as("una baja cancelada no se ejecuta").isZero();
    }

    @Test
    void alVencerElPlazoSeEliminanLosDatosDelNegocioYSeConservanLosPagos() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        assertThat(status(new Browser().post(calc.api() + "/quotes", QUOTE))).isEqualTo(201);
        grantPlan("ana@ejemplo.com", "PRO");
        jdbc.update("""
                insert into payment_transactions (id, tenant_id, subscription_id, provider, external_id, status, amount,
                    currency, paid_at, created_at, updated_at)
                select gen_random_uuid(), tenant_id, id, 'FAKE', 'pay-1', 'APPROVED', 9900, 'ARS', now(), now(), now()
                from tenant_subscriptions""");

        Browser beto = registerVerifiedAndLogin("Beto", "beto@ejemplo.com");
        Published betoCalc = publishTemplate(beto, "Catering Beto", "Eventos Beto", "catering");

        assertThat(status(requestDeletion(ana, PASSWORD, "ELIMINAR"))).isEqualTo(200);
        String tenantId = jdbc.queryForObject("select id::text from tenants where deletion_requested_at is not null",
                String.class);

        clock.advance(Duration.ofDays(29));
        assertThat(deletions.runDue()).as("todavía no venció").isZero();

        emails().clear();
        clock.advance(Duration.ofDays(2));
        assertThat(deletions.runDue()).isEqualTo(1);

        for (String table : new String[]{"calculators", "calculator_versions", "quote_simulations", "quote_requests",
                "quote_status_history", "business_profiles", "business_settings", "usage_counters"}) {
            assertThat(count("select count(*) from " + table + " where tenant_id = ?::uuid", tenantId))
                    .as(table).isZero();
        }
        assertThat(count("select count(*) from tenant_memberships where tenant_id = ?::uuid", tenantId)).isZero();
        assertThat(count("select count(*) from payment_transactions where tenant_id = ?::uuid", tenantId))
                .as("los pagos se conservan").isEqualTo(1);
        assertThat(count("select count(*) from tenant_subscriptions where tenant_id = ?::uuid", tenantId)).isEqualTo(1);
        Map<String, Object> tenant = jdbc.queryForMap("select status, name, slug from tenants where id = ?::uuid", tenantId);
        assertThat(tenant.get("status")).isEqualTo("DELETED");
        assertThat(tenant.get("name")).isEqualTo("Cuenta eliminada");
        assertThat((String) tenant.get("slug")).startsWith("eliminado-");

        assertThat(count("select count(*) from users where email = 'ana@ejemplo.com'")).isZero();
        assertThat(count("select count(*) from users where deleted_at is not null and first_name = 'Usuario'")).isEqualTo(1);
        assertThat(count("select count(*) from email_delivery_logs where recipient = 'ana@ejemplo.com'")).isZero();
        assertThat(emails().lastTo("ana@ejemplo.com")).map(EmailMessage::template).contains("ACCOUNT_DELETED");
        assertThat(status(ana.get("/api/v1/auth/me"))).as("la sesión se cerró").isEqualTo(401);
        assertThat(status(new Browser().post("/api/v1/auth/login", loginJson("ana@ejemplo.com", PASSWORD))))
                .isEqualTo(401);
        assertThat(status(new Browser().get(calc.api()))).isEqualTo(404);

        // El otro negocio no se toca.
        assertThat(status(new Browser().get(betoCalc.api()))).isEqualTo(200);
        assertThat(status(beto.get("/api/v1/business/profile"))).isEqualTo(200);

        // Las versiones siguen siendo inmutables para cualquier otro negocio.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("delete from calculator_versions"))
                .hasMessageContaining("inmutable");
        assertThat(deletions.runDue()).as("idempotente").isZero();
    }

    // ------------------------------------------------------------------ eliminación desde el panel de Climind

    @Test
    void elAdminEliminaDefinitivamenteUnNegocioYElEmailQuedaLibre() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        assertThat(status(new Browser().post(calc.api() + "/quotes", QUOTE))).isEqualTo(201);
        String tenantId = jdbc.queryForObject("select tenant_id::text from calculators limit 1", String.class);
        Browser admin = loginAsSuperAdmin();

        MvcResult noConfirm = admin.send(delete("/api/v1/admin/tenants/" + tenantId),
                "{\"confirmation\":\"borrar\",\"notifyOwner\":false}");
        assertThat(status(noConfirm)).isEqualTo(400);
        assertThat(status(ana.send(delete("/api/v1/admin/tenants/" + tenantId),
                "{\"confirmation\":\"ELIMINAR\",\"notifyOwner\":false}"))).as("solo SUPER_ADMIN").isEqualTo(403);

        emails().clear();
        MvcResult deleted = admin.send(delete("/api/v1/admin/tenants/" + tenantId),
                "{\"confirmation\":\"eliminar\",\"notifyOwner\":false}");
        assertThat(status(deleted)).as(body(deleted)).isEqualTo(204);

        assertThat(count("select count(*) from calculators where tenant_id = ?::uuid", tenantId)).isZero();
        assertThat(count("select count(*) from quote_requests where tenant_id = ?::uuid", tenantId)).isZero();
        assertThat((String) jdbc.queryForObject("select status from tenants where id = ?::uuid", String.class, tenantId))
                .isEqualTo("DELETED");
        assertThat(count("select count(*) from users where email = 'ana@ejemplo.com'")).isZero();
        assertThat(emails().lastTo("ana@ejemplo.com")).as("sin aviso si no se pidió").isEmpty();
        assertThat(count("select count(*) from audit_logs where action = 'ACCOUNT_DELETED_BY_ADMIN'")).isEqualTo(1);
        assertThat(status(ana.get("/api/v1/auth/me"))).isEqualTo(401);
        assertThat(status(new Browser().get(calc.api()))).isEqualTo(404);

        // Desaparece del listado salvo que se filtre por eliminados.
        assertThat(body(admin.get("/api/v1/admin/tenants"))).doesNotContain(tenantId);
        assertThat(body(admin.get("/api/v1/admin/tenants?status=DELETED"))).contains(tenantId);

        // El email queda libre: se puede registrar de nuevo.
        assertThat(status(new Browser().post("/api/v1/auth/register", registerJson("Ana", "ana@ejemplo.com"))))
                .isEqualTo(201);
    }

    @Test
    void laPaginaDeUnCotizadorNoDisponibleLoDiceSinRevelarPorQue() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza de hogares", "limpieza");
        assertThat(status(requestDeletion(ana, PASSWORD, "ELIMINAR"))).isEqualTo(200);
        MvcResult result = new Browser().get(calc.page());
        assertThat(status(result)).isEqualTo(404);
        assertThat(body(result)).contains("Este cotizador no está disponible").doesNotContain("baja");
    }
}
