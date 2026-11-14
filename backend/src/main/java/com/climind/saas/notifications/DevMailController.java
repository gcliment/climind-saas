package com.climind.saas.notifications;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SOLO DESARROLLO: bandeja de los últimos emails "enviados" por {@link LoggingEmailSender}.
 * Permite probar verificación y recuperación sin un servidor SMTP (y la usan los tests E2E).
 * No existe con el perfil prod (y SecurityConfig además deniega {@code /api/dev/**} fuera de dev).
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev/emails")
public class DevMailController {

    private static final Pattern LINK = Pattern.compile("href=\"([^\"]+)\"");

    private final EmailSender sender;

    public DevMailController(EmailSender sender) {
        this.sender = sender;
    }

    public record DevEmail(String template, String to, String subject, List<String> links) {
    }

    @GetMapping
    public List<DevEmail> recent(@RequestParam(required = false) String to) {
        if (!(sender instanceof LoggingEmailSender logging)) {
            return List.of();
        }
        return logging.recent().stream()
                .filter(m -> to == null || m.to().equalsIgnoreCase(to))
                .map(m -> new DevEmail(m.template(), m.to(), m.subject(), links(m.html())))
                .toList();
    }

    private static List<String> links(String html) {
        Matcher matcher = LINK.matcher(html);
        List<String> result = new java.util.ArrayList<>();
        while (matcher.find()) {
            result.add(matcher.group(1).replace("&amp;", "&"));
        }
        return result;
    }
}
