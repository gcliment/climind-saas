package com.climind.saas.account;

import com.climind.saas.auth.AccountSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Baja de los emails de producto con el enlace del email (sin sesión). Solo POST: los antivirus de correo que
 * abren los enlaces (GET) no dan de baja a nadie. Acepta también la baja en un clic de los clientes de correo
 * (RFC 8058, {@code List-Unsubscribe-Post}).
 */
@Tag(name = "Público", description = "Cotizador público para los clientes de cada negocio")
@RestController
@RequestMapping("/api/v1/public/emails")
public class EmailPreferencesController {

    private final AccountSettingsService settings;

    public EmailPreferencesController(AccountSettingsService settings) {
        this.settings = settings;
    }

    @Operation(summary = "Darse de baja de los emails de producto")
    @PostMapping("/unsubscribe")
    public Map<String, String> unsubscribe(@RequestParam("t") String token) {
        if (!settings.unsubscribe(token)) {
            throw com.climind.saas.shared.web.ApiException.notFound(
                    "Este enlace ya no es válido. Podés cambiar tus preferencias desde Tu cuenta.");
        }
        return Map.of("message", "Listo: no vas a recibir más consejos ni resúmenes. Los avisos de tu cuenta (pagos, "
                + "consultas, seguridad) siguen llegando.");
    }
}
