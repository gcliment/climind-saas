package com.climind.saas.assistant;

import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.ratelimit.RateLimiter;
import com.climind.saas.shared.security.AppPrincipal;
import com.climind.saas.shared.util.ArMoney;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.subscriptions.EntitlementService;
import com.climind.saas.subscriptions.Entitlements;
import com.climind.saas.subscriptions.UsageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Asistente de ayuda del panel: responde preguntas sobre la app con la documentación del centro de ayuda y el
 * contexto de la cuenta. Con clave de API usa OpenAI; sin clave (o si el servicio falla) responde con las
 * preguntas frecuentes que mejor coinciden. La conversación la guarda el navegador; acá no se persiste.
 */
@Slf4j
@Service
public class AssistantService {

    static final int MAX_TURNS = 20;
    static final int MAX_TURN_CHARS = 2000;
    /** Turnos que se mandan al modelo: los últimos (alcanza para seguir el hilo y acota el costo). */
    static final int TURNS_SENT = 12;

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy",
            Locale.forLanguageTag("es-AR"));
    private static final String FAQ_URL = "/ayuda/preguntas-frecuentes";

    private final HelpKnowledge knowledge;
    private final ObjectProvider<AssistantModel> model;
    private final JdbcTemplate jdbc;
    private final EntitlementService entitlements;
    private final UsageService usage;
    private final RateLimiter rateLimiter;
    private final AppProperties properties;
    private final Clock clock;

    public AssistantService(HelpKnowledge knowledge, ObjectProvider<AssistantModel> model, JdbcTemplate jdbc,
                            EntitlementService entitlements, UsageService usage, RateLimiter rateLimiter,
                            AppProperties properties, Clock clock) {
        this.knowledge = knowledge;
        this.model = model;
        this.jdbc = jdbc;
        this.entitlements = entitlements;
        this.usage = usage;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
    }

    public record Message(
            @NotBlank(message = "Indicá quién escribió el mensaje.")
            String role,
            @NotBlank(message = "Escribí tu pregunta.")
            @Size(max = MAX_TURN_CHARS, message = "La pregunta es muy larga: resumila en menos de 2000 caracteres.")
            String content) {
    }

    public record ChatRequest(
            @NotEmpty(message = "Escribí tu pregunta.")
            @Size(max = MAX_TURNS, message = "La conversación es muy larga: empezá una nueva.")
            List<@Valid Message> messages) {
    }

    /** Un enlace de la ayuda para leer más. */
    public record Link(String title, String url) {
    }

    /** {@code source}: AI (redactada por el asistente) o FAQ (preguntas frecuentes, sin IA). */
    public record ChatReply(String reply, String source, List<Link> links) {
    }

    /** Si el panel muestra el asistente con IA o solo las preguntas frecuentes. */
    public record Status(boolean ai) {
    }

    public Status status() {
        return new Status(model.getIfAvailable() != null);
    }

    public ChatReply chat(AppPrincipal principal, ChatRequest request) {
        List<AssistantModel.Turn> turns = turns(request.messages());
        if (!rateLimiter.tryConsume(RateLimiter.Policy.ASSISTANT_USER, principal.userId().toString())) {
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_LIMIT",
                    "Llegaste al máximo de preguntas por ahora. Probá de nuevo más tarde o revisá las preguntas frecuentes.");
        }
        String question = turns.getLast().text();
        AssistantModel ai = model.getIfAvailable();
        if (ai != null) {
            try {
                Optional<String> answer = ai.reply(stableSystem(), userContext(principal), turns);
                if (answer.isPresent()) {
                    return new ChatReply(answer.get(), "AI", List.of());
                }
            } catch (RuntimeException e) {
                log.warn("El asistente no pudo responder ({}): se usan las preguntas frecuentes", e.toString());
            }
        }
        return faqReply(question);
    }

    /** Valida la forma de la conversación: alterna usuario/asistente y termina con una pregunta. */
    static List<AssistantModel.Turn> turns(List<Message> messages) {
        List<AssistantModel.Turn> turns = new ArrayList<>();
        for (Message m : messages) {
            String role = m.role().strip().toLowerCase(Locale.ROOT);
            boolean fromUser = switch (role) {
                case "user" -> true;
                case "assistant" -> false;
                default -> throw ApiException.badRequest("INVALID_CONVERSATION", "La conversación no es válida.");
            };
            turns.add(new AssistantModel.Turn(fromUser, m.content().strip()));
        }
        if (!turns.getLast().fromUser()) {
            throw ApiException.badRequest("INVALID_CONVERSATION", "La conversación tiene que terminar con tu pregunta.");
        }
        List<AssistantModel.Turn> recent = new ArrayList<>(turns.subList(Math.max(0, turns.size() - TURNS_SENT), turns.size()));
        while (!recent.getFirst().fromUser()) {
            recent.removeFirst();
        }
        for (int i = 1; i < recent.size(); i++) {
            if (recent.get(i).fromUser() == recent.get(i - 1).fromUser()) {
                throw ApiException.badRequest("INVALID_CONVERSATION", "La conversación no es válida.");
            }
        }
        return recent;
    }

    ChatReply faqReply(String question) {
        List<HelpKnowledge.Faq> found = knowledge.search(question, 2);
        String contact = supportChannels();
        if (found.isEmpty()) {
            return new ChatReply("No encontré una respuesta para eso en la ayuda. Probá contarlo con otras palabras"
                    + " o revisá las preguntas frecuentes." + (contact.isEmpty() ? "" : " También podés escribirnos: " + contact + "."),
                    "FAQ", List.of(new Link("Preguntas frecuentes", FAQ_URL), new Link("Centro de ayuda", "/ayuda")));
        }
        StringBuilder reply = new StringBuilder("Esto es lo que encontré en las preguntas frecuentes:");
        for (HelpKnowledge.Faq faq : found) {
            reply.append("\n\n**").append(faq.question()).append("**\n").append(faq.answer());
        }
        return new ChatReply(reply.toString(), "FAQ", List.of(new Link("Ver todas las preguntas frecuentes", FAQ_URL)));
    }

    private String stableSystem() {
        return knowledge.instructions() + "\n\n" + knowledge.documentation();
    }

    /** Lo que el asistente necesita saber de esta cuenta para dar una respuesta concreta. */
    String userContext(AppPrincipal principal) {
        StringBuilder out = new StringBuilder("CONTEXTO DE ESTA CONVERSACIÓN\n");
        out.append("Hoy es ").append(DAY.format(clock.instant().atZone(ZONE))).append(".\n");
        jdbc.query("SELECT first_name, email_verified_at FROM users WHERE id = ?", rs -> {
            out.append("Usuario: ").append(rs.getString("first_name"))
                    .append(rs.getTimestamp("email_verified_at") == null ? " (todavía no confirmó su email)" : "")
                    .append(".\n");
        }, principal.userId());

        UUID tenantId = principal.tenantId();
        if (tenantId != null) {
            jdbc.query("""
                    SELECT coalesce(p.business_name, t.name) AS name, p.industry, t.status, t.deletion_scheduled_for,
                           t.onboarding_completed_at
                    FROM tenants t LEFT JOIN business_profiles p ON p.tenant_id = t.id WHERE t.id = ?
                    """, rs -> {
                out.append("Negocio: ").append(rs.getString("name"));
                if (rs.getString("industry") != null) {
                    out.append(" (rubro: ").append(rs.getString("industry").toLowerCase(Locale.ROOT)).append(')');
                }
                out.append(".\n");
                if ("SUSPENDED".equals(rs.getString("status"))) {
                    out.append("ATENCIÓN: la cuenta está suspendida por el equipo de Climind; sus cotizadores no están"
                            + " disponibles. Tiene que escribir a soporte.\n");
                }
                Timestamp deletion = rs.getTimestamp("deletion_scheduled_for");
                if (deletion != null) {
                    out.append("ATENCIÓN: pidió la baja de su cuenta; se elimina el ")
                            .append(DAY.format(deletion.toInstant().atZone(ZONE)))
                            .append(". Mientras tanto ninguno de sus cotizadores está disponible para sus clientes"
                                    + " (la página pública dice «Este cotizador no está disponible»). La puede cancelar"
                                    + " en Tu cuenta → Cancelar la baja.\n");
                }
                if (rs.getTimestamp("onboarding_completed_at") == null) {
                    out.append("Todavía no terminó la guía inicial.\n");
                }
            }, tenantId);

            Entitlements e = entitlements.forTenant(tenantId);
            UsageService.Usage quotes = usage.quoteUsage(tenantId, e);
            out.append("Plan actual: ").append(e.planName()).append(e.paid() ? " (pago)" : " (gratis)")
                    .append(". Cotizaciones este período: ").append(quotes.used()).append(" de ").append(quotes.limit())
                    .append(". Cotizadores publicados permitidos: ").append(e.maxPublishedCalculators()).append(".\n");

            List<String> calcs = jdbc.query("""
                    SELECT name, status FROM calculators WHERE tenant_id = ? AND status <> 'ARCHIVADO'
                    ORDER BY updated_at DESC LIMIT 10
                    """, (rs, i) -> "- " + rs.getString("name") + ": " + statusLabel(rs.getString("status")), tenantId);
            out.append(calcs.isEmpty() ? "Todavía no creó ningún cotizador.\n"
                    : "Sus cotizadores:\n" + String.join("\n", calcs) + "\n");
        } else {
            out.append("Es un usuario del equipo de Climind (sin negocio propio).\n");
        }

        List<String> plans = jdbc.query("""
                SELECT name, price, is_free, max_published_calculators, max_monthly_quotes, csv_export, custom_branding
                FROM subscription_plans WHERE active_for_new OR is_free ORDER BY sort_order
                """, (rs, i) -> "- " + rs.getString("name") + ": "
                + (rs.getBoolean("is_free") ? "gratis" : ArMoney.format(rs.getBigDecimal("price"), 0) + " por mes")
                + ", " + rs.getInt("max_published_calculators") + " cotizador(es) publicado(s), "
                + rs.getInt("max_monthly_quotes") + " cotizaciones por mes"
                + (rs.getBoolean("csv_export") ? ", exportación CSV" : "")
                + (rs.getBoolean("custom_branding") ? ", color propio y sin marca de Climind" : "")
                + (rs.getBoolean("is_free") ? "" : ", cotizador a medida sin costo adicional"));
        out.append("Planes vigentes (precios en pesos argentinos):\n").append(String.join("\n", plans)).append('\n');

        String contact = supportChannels();
        out.append(contact.isEmpty() ? "Soporte: por ahora solo el centro de ayuda (/ayuda).\n"
                : "Canales de soporte del equipo de Climind: " + contact + ".\n");
        return out.toString();
    }

    private String supportChannels() {
        List<String> channels = new ArrayList<>();
        String whatsapp = properties.getSupportWhatsapp() == null ? "" : properties.getSupportWhatsapp().replaceAll("\\D", "");
        if (!whatsapp.isEmpty()) {
            channels.add("WhatsApp https://wa.me/" + whatsapp);
        }
        String email = properties.getSupportEmail() == null ? "" : properties.getSupportEmail().strip();
        if (!email.isEmpty()) {
            channels.add("email " + email);
        }
        return String.join(" o ", channels);
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "BORRADOR" -> "borrador (sin publicar)";
            case "PUBLICADO" -> "publicado";
            case "PAUSADO" -> "pausado";
            default -> status.toLowerCase(Locale.ROOT);
        };
    }

}
