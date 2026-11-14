package com.climind.saas.account;

import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.ratelimit.RateLimiter;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.storage.FileStorage;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exportación completa de los datos de un negocio (portabilidad): un ZIP con archivos JSON legibles y el logo.
 * Disponible en todos los planes. Nunca incluye secretos: ni contraseñas, ni tokens de enlaces, ni hashes de
 * visitantes. Las consultas se exportan con todos sus datos porque son del negocio.
 */
@Service
public class AccountExportService {

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    /** Archivo del ZIP → consulta SQL que devuelve un único valor JSON (texto). {@code ?} = id del negocio. */
    private static final Map<String, String> FILES = new java.util.LinkedHashMap<>();

    static {
        FILES.put("negocio.json", """
                SELECT json_build_object(
                  'negocio', (SELECT row_to_json(x) FROM (SELECT t.name AS nombre_interno, t.slug AS direccion_publica,
                                t.country AS pais, t.currency AS moneda, t.timezone AS zona_horaria,
                                t.onboarding_completed_at, t.created_at FROM tenants t WHERE t.id = ?) x),
                  'perfil', (SELECT row_to_json(x) FROM (SELECT p.business_name, p.industry, p.description, p.phone,
                                p.whatsapp, p.contact_email, p.website, p.created_at, p.updated_at
                                FROM business_profiles p WHERE p.tenant_id = ?) x),
                  'configuracion', (SELECT row_to_json(x) FROM (SELECT s.notify_new_lead_email, s.notify_usage_email,
                                s.lead_retention_months, s.updated_at FROM business_settings s WHERE s.tenant_id = ?) x)
                )::text
                """);
        FILES.put("usuarios.json", """
                SELECT coalesce(json_agg(x ORDER BY x.created_at), '[]'::json)::text FROM (
                  SELECT u.first_name, u.last_name, u.email, m.role, u.email_verified_at, u.last_login_at,
                         u.terms_accepted_at, u.product_emails, u.created_at
                  FROM tenant_memberships m JOIN users u ON u.id = m.user_id WHERE m.tenant_id = ?) x
                """);
        FILES.put("cotizadores.json", """
                SELECT coalesce(json_agg(x ORDER BY x.created_at), '[]'::json)::text FROM (
                  SELECT c.id, c.name, c.description, c.slug, c.status, c.currency, c.template_code,
                         c.draft_config AS configuracion_borrador, c.appearance AS apariencia, c.published_at,
                         c.archived_at, c.created_at, c.updated_at,
                         (SELECT coalesce(json_agg(v ORDER BY v.version_no), '[]'::json) FROM (
                            SELECT cv.version_no, cv.name, cv.description, cv.config AS configuracion,
                                   cv.appearance AS apariencia, cv.published_at
                            FROM calculator_versions cv WHERE cv.calculator_id = c.id) v) AS versiones_publicadas
                  FROM calculators c WHERE c.tenant_id = ?) x
                """);
        FILES.put("cotizaciones.json", """
                SELECT coalesce(json_agg(x ORDER BY x.created_at), '[]'::json)::text FROM (
                  SELECT s.code AS codigo, s.calculator_name AS cotizador, s.answers AS respuestas,
                         s.result AS desglose, s.total, s.currency AS moneda, s.disclaimer AS aclaracion,
                         s.valid_until AS valida_hasta, s.whatsapp_clicked_at, s.redacted_at AS anonimizada,
                         s.created_at
                  FROM quote_simulations s WHERE s.tenant_id = ?) x
                """);
        FILES.put("consultas.json", """
                SELECT coalesce(json_agg(x ORDER BY x.created_at), '[]'::json)::text FROM (
                  SELECT r.code AS codigo_cotizacion, r.calculator_name AS cotizador, r.total, r.currency AS moneda,
                         r.summary AS resumen, r.customer_name AS nombre, r.customer_whatsapp AS whatsapp,
                         r.customer_email AS email, r.comments AS comentarios, r.consent_at AS consentimiento,
                         r.status AS estado, r.status_changed_at, r.anonymized_at AS anonimizada, r.created_at,
                         (SELECT coalesce(json_agg(h ORDER BY h.created_at), '[]'::json) FROM (
                            SELECT qh.kind AS tipo, qh.from_status AS desde, qh.to_status AS hacia, qh.note AS nota,
                                   qh.created_at
                            FROM quote_status_history qh WHERE qh.request_id = r.id) h) AS historial
                  FROM quote_requests r WHERE r.tenant_id = ?) x
                """);
        FILES.put("plan-y-pagos.json", """
                SELECT json_build_object(
                  'suscripciones', (SELECT coalesce(json_agg(x ORDER BY x.created_at), '[]'::json) FROM (
                      SELECT ts.plan_name AS plan, ts.status AS estado, ts.price AS precio, ts.currency AS moneda,
                             ts.current_period_start, ts.current_period_end, ts.canceled_at, ts.ended_at,
                             ts.end_reason, ts.created_at
                      FROM tenant_subscriptions ts WHERE ts.tenant_id = ?) x),
                  'pagos', (SELECT coalesce(json_agg(x ORDER BY x.created_at), '[]'::json) FROM (
                      SELECT p.status AS estado, p.amount AS importe, p.currency AS moneda, p.charge_date,
                             p.paid_at, p.created_at
                      FROM payment_transactions p WHERE p.tenant_id = ?) x)
                )::text
                """);
    }

    private static final String README = """
            Exportación de datos de Climind Cotiza
            =======================================

            Generada el %s (hora de Argentina).

            Archivos (formato JSON, UTF-8):
            - negocio.json        Datos del negocio, perfil público y configuración.
            - usuarios.json       Personas con acceso al panel.
            - cotizadores.json    Cotizadores con su borrador y todas las versiones publicadas (preguntas y precios).
            - cotizaciones.json   Cotizaciones emitidas con las respuestas del cliente y el desglose del importe.
            - consultas.json      Consultas de clientes (datos de contacto, estado, notas e historial).
            - plan-y-pagos.json   Suscripciones y pagos registrados.
            - logo.*              Logo del negocio, si cargaste uno.

            Las fechas están en UTC (formato ISO 8601). Los importes están en la moneda indicada.
            Esta copia contiene datos personales de tus clientes: guardala en un lugar seguro.
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final FileStorage storage;
    private final AuditLogService audit;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public AccountExportService(JdbcTemplate jdbc, JsonMapper jsonMapper, FileStorage storage, AuditLogService audit,
                                RateLimiter rateLimiter, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.storage = storage;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public record Export(String fileName, byte[] content) {
    }

    @Transactional
    public Export export(UUID tenantId, UUID actorUserId) {
        if (!rateLimiter.tryConsume(RateLimiter.Policy.ACCOUNT_EXPORT, tenantId.toString())) {
            throw ApiException.tooManyRequests();
        }
        String stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(clock.instant().atZone(ZONE));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            add(zip, "LEEME.txt", README.formatted(stamp).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, String> file : FILES.entrySet()) {
                add(zip, file.getKey(), json(file.getValue(), tenantId));
            }
            logo(tenantId).ifPresent(logo -> add(zip, logo.name(), logo.content()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        audit.record(AuditAction.ACCOUNT_DATA_EXPORTED, actorUserId, tenantId);
        String date = DateTimeFormatter.ISO_LOCAL_DATE.format(clock.instant().atZone(ZONE));
        return new Export("climind-cotiza-datos-" + date + ".zip", bytes.toByteArray());
    }

    private byte[] json(String sql, UUID tenantId) {
        int params = (int) sql.chars().filter(c -> c == '?').count();
        Object[] args = new Object[params];
        java.util.Arrays.fill(args, tenantId);
        String raw = jdbc.queryForObject(sql, String.class, args);
        // Se re-serializa con indentación para que se pueda leer en cualquier editor de texto.
        return jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(jsonMapper.readTree(raw == null ? "null" : raw))
                .getBytes(StandardCharsets.UTF_8);
    }

    private record Logo(String name, byte[] content) {
    }

    private java.util.Optional<Logo> logo(UUID tenantId) {
        return jdbc.query("""
                        SELECT a.storage_key, a.content_type FROM business_profiles p
                        JOIN business_assets a ON a.id = p.logo_asset_id WHERE p.tenant_id = ?
                        """, (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, tenantId).stream().findFirst()
                .flatMap(row -> storage.get(row[0]).map(resource -> new Logo("logo." + extension(row[1]),
                        read(resource))));
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    private static byte[] read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void add(ZipOutputStream zip, String name, byte[] content) {
        try {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
