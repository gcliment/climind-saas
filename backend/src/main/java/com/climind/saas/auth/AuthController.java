package com.climind.saas.auth;

import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.security.AppPrincipal;
import com.climind.saas.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Autenticación")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String CHECK_INBOX =
            "Si los datos son correctos, en unos minutos vas a recibir un email. Revisá también la carpeta de spam.";

    private final AuthService authService;
    private final SessionService sessionService;
    private final AuditLogService audit;

    public AuthController(AuthService authService, SessionService sessionService, AuditLogService audit) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.audit = audit;
    }

    @Operation(summary = "Obtener el token CSRF",
            description = "Deja la cookie XSRF-TOKEN; el frontend la reenvía en el header X-XSRF-TOKEN.")
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @Operation(summary = "Registrar una cuenta nueva (crea también el negocio)")
    @PostMapping("/register")
    public ResponseEntity<AuthDtos.MessageResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AuthDtos.MessageResponse(
                "Te enviamos un email para confirmar tu cuenta. Revisá también la carpeta de spam."));
    }

    @Operation(summary = "Confirmar el email con el token recibido")
    @PostMapping("/verify-email")
    public AuthDtos.MessageResponse verifyEmail(@Valid @RequestBody AuthDtos.TokenRequest request) {
        authService.verifyEmail(request.token());
        return new AuthDtos.MessageResponse("¡Listo! Confirmaste tu email. Ya podés ingresar.");
    }

    @Operation(summary = "Reenviar el email de verificación")
    @PostMapping("/resend-verification")
    public ResponseEntity<AuthDtos.MessageResponse> resend(@Valid @RequestBody AuthDtos.EmailRequest request) {
        authService.resendVerification(request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AuthDtos.MessageResponse(CHECK_INBOX));
    }

    @Operation(summary = "Iniciar sesión", description = "Crea la cookie de sesión HttpOnly CLIMIND_SESSION.")
    @PostMapping("/login")
    public AuthDtos.MeResponse login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                     HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AppPrincipal principal = authService.authenticate(request);
        sessionService.establish(principal, httpRequest, httpResponse);
        return authService.me(principal);
    }

    @Operation(summary = "Cerrar sesión")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        CurrentUser.find().ifPresent(p -> audit.record(AuditAction.LOGOUT, p.userId(), p.tenantId()));
        sessionService.logout(request, response);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Usuario autenticado y negocio activo")
    @GetMapping("/me")
    public AuthDtos.MeResponse me() {
        return authService.me(CurrentUser.require());
    }

    @Operation(summary = "Pedir un enlace para restablecer la contraseña")
    @PostMapping("/forgot-password")
    public ResponseEntity<AuthDtos.MessageResponse> forgot(@Valid @RequestBody AuthDtos.EmailRequest request) {
        authService.requestPasswordReset(request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AuthDtos.MessageResponse(CHECK_INBOX));
    }

    @Operation(summary = "Restablecer la contraseña con el token recibido",
            description = "Cierra todas las sesiones abiertas del usuario.")
    @PostMapping("/reset-password")
    public AuthDtos.MessageResponse reset(@Valid @RequestBody AuthDtos.ResetPasswordRequest request) {
        authService.resetPassword(request);
        return new AuthDtos.MessageResponse("Tu contraseña se actualizó. Ya podés ingresar con la nueva.");
    }
}
