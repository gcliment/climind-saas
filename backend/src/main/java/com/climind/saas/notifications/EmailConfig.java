package com.climind.saas.notifications;

import com.climind.saas.shared.config.AppProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class EmailConfig {

    @Bean
    public EmailSender emailSender(AppProperties properties, ObjectProvider<JavaMailSender> javaMailSender,
                                   Environment environment) {
        String provider = properties.getMail().getProvider();
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        return switch (provider) {
            case "smtp" -> new SmtpEmailSender(javaMailSender.getObject(), properties.getMail().getFrom());
            case "log" -> {
                if (prod) {
                    throw new IllegalStateException(
                            "MAIL_PROVIDER=log no está permitido en producción: configurá SMTP (ver docs/ENVIRONMENT.md)");
                }
                yield new LoggingEmailSender();
            }
            default -> throw new IllegalStateException("MAIL_PROVIDER desconocido: " + provider);
        };
    }
}
