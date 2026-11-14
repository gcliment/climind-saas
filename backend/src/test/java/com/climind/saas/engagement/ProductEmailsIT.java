package com.climind.saas.engagement;

import com.climind.saas.notifications.EmailMessage;
import com.climind.saas.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Emails de producto: una sola vez, solo a quien los acepta, con baja en un clic. */
class ProductEmailsIT extends IntegrationTest {

    private static final Pattern UNSUBSCRIBE = Pattern.compile("/emails/baja\\?t=([A-Za-z0-9_-]{43})");

    @Autowired
    private ProductEmailJobs jobs;

    private List<EmailMessage> sent(String template) {
        return emails().recent().stream().filter(m -> m.template().equals(template)).toList();
    }

    @Test
    void elRecordatorioDeActivacionLlegaUnaVezAQuienNoPublicoNada() throws Exception {
        registerVerifiedAndLogin("Ana", "ana@ejemplo.com");       // sin cotizador publicado
        Browser beto = registerVerifiedAndLogin("Beto", "beto@ejemplo.com");
        publishTemplate(beto, "Limpiezas Beto", "Limpieza", "limpieza");
        registerVerifiedAndLogin("Caro", "caro@ejemplo.com");
        jdbc.update("update users set product_emails = false where email = 'caro@ejemplo.com'");

        emails().clear();
        clock.advance(Duration.ofDays(1));
        assertThat(jobs.sendActivationReminders()).as("todavía es pronto").isZero();

        clock.advance(Duration.ofDays(2));
        assertThat(jobs.sendActivationReminders()).isEqualTo(1);
        List<EmailMessage> reminders = sent("ACTIVATION_REMINDER");
        assertThat(reminders).extracting(EmailMessage::to).containsExactly("ana@ejemplo.com");
        EmailMessage reminder = reminders.getFirst();
        assertThat(reminder.html()).contains("No quiero recibir más estos emails", "/app/cotizadores");
        assertThat(reminder.unsubscribeUrl()).contains("/api/v1/public/emails/unsubscribe?t=");

        assertThat(jobs.sendActivationReminders()).as("una sola vez").isZero();
        clock.advance(Duration.ofDays(20));
        assertThat(jobs.sendActivationReminders()).isZero();
    }

    @Test
    void losEmailsTransaccionalesNoTienenEnlaceDeBaja() throws Exception {
        registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        EmailMessage welcome = emails().recent().stream().filter(m -> m.template().equals("VERIFY_EMAIL")).findFirst()
                .orElseThrow();
        assertThat(welcome.unsubscribeUrl()).isNull();
        assertThat(welcome.html()).doesNotContain("No quiero recibir más estos emails");
    }

    @Test
    void elEnlaceDelEmailDaDeBajaSinIniciarSesion() throws Exception {
        registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        clock.advance(Duration.ofDays(3));
        emails().clear();
        jobs.sendActivationReminders();
        Matcher m = UNSUBSCRIBE.matcher(sent("ACTIVATION_REMINDER").getFirst().html());
        assertThat(m.find()).isTrue();

        // Un GET (por ejemplo, el antivirus del correo abriendo el enlace) no da de baja a nadie.
        assertThat(mockMvc.perform(anonymousGet("/api/v1/public/emails/unsubscribe?t=" + m.group(1)))
                .andReturn().getResponse().getStatus()).isNotEqualTo(200);
        assertThat(jdbc.queryForObject("select product_emails from users", Boolean.class)).isTrue();

        assertThat(mockMvc.perform(anonymousPost("/api/v1/public/emails/unsubscribe?t=" + m.group(1)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select product_emails from users", Boolean.class)).isFalse();
        assertThat(mockMvc.perform(anonymousPost("/api/v1/public/emails/unsubscribe?t=" + "x".repeat(43)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void elResumenMensualCuentaLaActividadDelMesAnteriorYSeMandaUnaVez() throws Exception {
        Browser ana = registerVerifiedAndLogin("Ana", "ana@ejemplo.com");
        Published calc = publishTemplate(ana, "Limpiezas Ana", "Limpieza", "limpieza");
        new Browser().post(calc.api() + "/quotes", """
                {"answers":{"tipo":"profunda","m2":60,"ambientes":3,"frecuencia":"unica"},
                 "contact":{"name":"Carla","whatsapp":"11 2345-6789","consent":true}}""");
        new Browser().post(calc.api() + "/quotes", """
                {"answers":{"tipo":"basica","m2":40,"ambientes":2,"frecuencia":"unica"}}""");
        registerVerifiedAndLogin("Beto", "beto@ejemplo.com"); // nunca publicó: no recibe resumen

        emails().clear();
        assertThat(jobs.sendMonthlySummaries()).as("el 7 de octubre no se manda").isZero();

        clock.set(Instant.parse("2026-11-02T12:00:00Z")); // 2 de noviembre en Buenos Aires
        assertThat(jobs.sendMonthlySummaries()).isEqualTo(1);
        EmailMessage summary = sent("MONTHLY_SUMMARY").getFirst();
        assertThat(summary.to()).isEqualTo("ana@ejemplo.com");
        assertThat(summary.html()).contains("octubre de 2026", "Limpiezas Ana");
        assertThat(summary.text()).contains("Cotizaciones", "2", "Consultas", "1", "Sin responder");
        assertThat(jobs.sendMonthlySummaries()).as("una vez por mes").isZero();
    }
}
