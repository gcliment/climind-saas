package com.climind.saas.notifications;

import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.util.Ids;
import com.climind.saas.shared.util.Masking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Envío de emails transaccionales.
 * <ul>
 *   <li>Se envían recién después del commit de la transacción que los origina (no salen emails de
 *       operaciones revertidas).</li>
 *   <li>En segundo plano (configurable), para no bloquear la respuesta HTTP.</li>
 *   <li>Se registra cada entrega en {@code email_delivery_logs} sin el cuerpo (puede contener tokens).
 *       Los emails con token no se reintentan: el usuario puede pedir uno nuevo desde la interfaz.</li>
 * </ul>
 */
@Slf4j
@Service
public class EmailService {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");

    private final EmailSender sender;
    private final TemplateEngine templateEngine;
    private final TaskExecutor executor;
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    private final Clock clock;

    public EmailService(EmailSender sender, TemplateEngine templateEngine,
                        @Qualifier("backgroundExecutor") TaskExecutor executor, JdbcTemplate jdbc,
                        AppProperties properties, Clock clock) {
        this.sender = sender;
        this.templateEngine = templateEngine;
        this.executor = executor;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public void send(MailTemplate template, String to, Map<String, Object> variables, UUID userId, UUID tenantId) {
        EmailMessage message = render(template, to, variables);
        Runnable task = () -> deliver(message, userId, tenantId);
        Runnable dispatch = properties.getMail().isAsync() ? () -> executor.execute(task) : task;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    /**
     * Variable opcional {@code unsubscribeToken} (emails de producto): agrega al pie el enlace "darme de baja" y
     * el encabezado de baja en un clic. Los emails transaccionales no la llevan.
     */
    EmailMessage render(MailTemplate template, String to, Map<String, Object> variables) {
        Map<String, Object> vars = new HashMap<>(variables);
        String appUrl = properties.publicUrlWithoutTrailingSlash();
        vars.put("appUrl", appUrl);
        vars.put("subject", template.subject());
        Object token = vars.remove("unsubscribeToken");
        String oneClickUrl = null;
        if (token != null) {
            vars.put("unsubscribeUrl", appUrl + "/emails/baja?t=" + token);
            oneClickUrl = appUrl + "/api/v1/public/emails/unsubscribe?t=" + token;
        }
        Context context = new Context(ES_AR, vars);
        String html = templateEngine.process("email/" + template.file(), context);
        return new EmailMessage(template.name(), to, template.subject(), html, toPlainText(html), oneClickUrl);
    }

    private void deliver(EmailMessage message, UUID userId, UUID tenantId) {
        String status = "SENT";
        String error = null;
        try {
            sender.send(message);
        } catch (RuntimeException e) {
            status = "FAILED";
            error = e.getClass().getSimpleName() + ": " + truncate(String.valueOf(e.getMessage()), 400);
            log.warn("No se pudo enviar el email {} a {}: {}", message.template(), Masking.email(message.to()), error);
        }
        try {
            jdbc.update("""
                            INSERT INTO email_delivery_logs (id, template, recipient, user_id, tenant_id, status, error, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    Ids.newId(), message.template(),
                    MailTemplate.valueOf(message.template()).logRecipient() ? message.to() : "eliminado",
                    userId, tenantId, status, error,
                    Timestamp.from(clock.instant()));
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar la entrega del email {}", message.template(), e);
        }
    }

    /** Versión de texto plano para clientes de correo sin HTML. */
    static String toPlainText(String html) {
        String text = html
                .replaceAll("(?is)<(style|head)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<a [^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", "$2: $1")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h[1-6]|tr|li)>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text.lines().map(String::strip).filter(l -> !l.isEmpty())
                .reduce((a, b) -> a + "\n\n" + b).orElse("");
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
