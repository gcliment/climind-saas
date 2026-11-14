package com.climind.saas.notifications;

import com.climind.saas.shared.util.Masking;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Transporte de desarrollo y tests: no envía nada, guarda los últimos emails en memoria
 * (consultables en dev vía {@code GET /api/dev/emails}). Prohibido en producción (ver {@link EmailConfig}).
 */
@Slf4j
public class LoggingEmailSender implements EmailSender {

    private static final int CAPACITY = 50;
    private final Deque<EmailMessage> recent = new ArrayDeque<>();

    @Override
    public synchronized void send(EmailMessage message) {
        log.info("[email:log] {} → {} | {}", message.template(), Masking.email(message.to()), message.subject());
        recent.addFirst(message);
        while (recent.size() > CAPACITY) {
            recent.removeLast();
        }
    }

    public synchronized List<EmailMessage> recent() {
        return List.copyOf(recent);
    }

    public synchronized Optional<EmailMessage> lastTo(String to) {
        return recent.stream().filter(m -> m.to().equalsIgnoreCase(to)).findFirst();
    }

    public synchronized void clear() {
        recent.clear();
    }
}
