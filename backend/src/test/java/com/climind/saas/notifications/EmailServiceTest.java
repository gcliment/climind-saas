package com.climind.saas.notifications;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailServiceTest {

    @Test
    void versionTextoConservaLosLinks() {
        String text = EmailService.toPlainText("""
                <html><head><style>p{}</style></head><body>
                <p>Hola <strong>Ana</strong>:</p>
                <p><a href="https://cotiza.climind-ia.com/verificar?token=abc">Confirmar mi email</a></p>
                </body></html>""");
        assertThat(text).contains("Hola Ana:")
                .contains("Confirmar mi email: https://cotiza.climind-ia.com/verificar?token=abc")
                .doesNotContain("<", "p{}");
    }

    @Test
    void elToStringDelMensajeNoExponeElCuerpo() {
        EmailMessage message = new EmailMessage("VERIFY_EMAIL", "ana@ejemplo.com", "Asunto",
                "<a href='?token=secreto'>x</a>", "token=secreto");
        assertThat(message.toString()).doesNotContain("secreto", "ana@ejemplo.com");
    }
}
