package com.climind.saas.notifications;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Envío por SMTP (configurable con {@code spring.mail.*}: Brevo, Resend, SES, Mailpit, etc.). */
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
            if (message.unsubscribeUrl() != null) {
                // Baja en un clic (RFC 8058): los clientes de correo muestran "Darse de baja".
                mime.addHeader("List-Unsubscribe", "<" + message.unsubscribeUrl() + ">");
                mime.addHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            }
            mailSender.send(mime);
        } catch (MessagingException e) {
            throw new MailSendException("No se pudo construir el email", e);
        }
    }
}
