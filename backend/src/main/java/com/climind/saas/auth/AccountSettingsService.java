package com.climind.saas.auth;

import com.climind.saas.notifications.EmailService;
import com.climind.saas.notifications.MailTemplate;
import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.ratelimit.RateLimiter;
import com.climind.saas.shared.util.Tokens;
import com.climind.saas.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Datos de la cuenta del usuario que ingresó: nombre, contraseña y preferencias de emails de producto.
 * Siempre sobre el usuario de la sesión (el id llega del principal, nunca del request).
 */
@Service
public class AccountSettingsService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessions;
    private final EmailService emails;
    private final AuditLogService audit;
    private final RateLimiter rateLimiter;
    private final AppProperties properties;
    private final Clock clock;

    public AccountSettingsService(UserRepository users, PasswordEncoder passwordEncoder, SessionService sessions,
                                  EmailService emails, AuditLogService audit, RateLimiter rateLimiter,
                                  AppProperties properties, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.emails = emails;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
    }

    public record UserAccount(String email, String firstName, String lastName, boolean productEmails) {
    }

    @Transactional(readOnly = true)
    public UserAccount get(UUID userId) {
        User user = require(userId);
        return new UserAccount(user.getEmail(), user.getFirstName(), user.getLastName(), user.isProductEmails());
    }

    @Transactional
    public UserAccount rename(UUID userId, String firstName, String lastName) {
        User user = require(userId);
        user.rename(firstName, lastName);
        audit.record(AuditAction.ACCOUNT_PROFILE_UPDATED, userId, null);
        return get(user);
    }

    @Transactional
    public UserAccount setProductEmails(UUID userId, boolean enabled) {
        User user = require(userId);
        if (user.isProductEmails() != enabled) {
            user.setProductEmails(enabled);
            audit.record(AuditAction.EMAIL_PREFERENCES_UPDATED, userId, null, "user", userId.toString(),
                    Map.of("productEmails", enabled));
        }
        return get(user);
    }

    /**
     * Cambio de contraseña desde el panel: exige la actual, cierra las demás sesiones (la actual sigue) y avisa
     * por email.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword, String confirmation,
                               String currentSessionId) {
        User user = require(userId);
        verifyPassword(user, currentPassword, "currentPassword");
        if (!newPassword.equals(confirmation)) {
            throw ApiException.field("passwordConfirmation", "Las contraseñas no coinciden.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.field("password", "La contraseña nueva tiene que ser distinta de la actual.");
        }
        PasswordPolicy.validate(newPassword, user.getEmail()).ifPresent(msg -> {
            throw ApiException.field("password", msg);
        });
        user.changePassword(passwordEncoder.encode(newPassword), clock.instant());
        audit.record(AuditAction.PASSWORD_CHANGED, userId, null);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessions.invalidateOthers(userId, currentSessionId);
            }
        });
        emails.send(MailTemplate.PASSWORD_CHANGED, user.getEmail(),
                Map.of("firstName", user.getFirstName(),
                        "resetLink", properties.publicUrlWithoutTrailingSlash() + "/recuperar"),
                userId, null);
    }

    /**
     * Confirma la contraseña del usuario antes de una acción sensible (p. ej. pedir la baja de la cuenta).
     *
     * @param field campo del formulario donde se informa el error
     */
    @Transactional(readOnly = true)
    public void confirmPassword(UUID userId, String password, String field) {
        verifyPassword(require(userId), password, field);
    }

    /** Baja de los emails de producto desde el enlace del email (sin sesión). */
    @Transactional
    public boolean unsubscribe(String token) {
        if (!Tokens.looksValid(token)) {
            return false;
        }
        Optional<User> user = users.findByEmailPrefsToken(token);
        user.ifPresent(u -> {
            if (u.isProductEmails()) {
                u.setProductEmails(false);
                audit.record(AuditAction.EMAIL_PREFERENCES_UPDATED, u.getId(), null, "user", u.getId().toString(),
                        Map.of("productEmails", false, "via", "link"));
            }
        });
        return user.isPresent();
    }

    /** Token del enlace de baja de emails de producto del usuario (lo crea si hace falta). */
    @Transactional
    public String unsubscribeToken(UUID userId) {
        return require(userId).ensureEmailPrefsToken();
    }

    /**
     * Baja de la cuenta: reemplaza los datos personales del usuario (los registros de pagos y auditoría siguen
     * referenciando la fila) y cierra todas sus sesiones.
     */
    @Transactional
    public void anonymize(UUID userId) {
        User user = require(userId);
        Instant now = clock.instant();
        user.anonymize("eliminado-" + userId + "@cuenta-eliminada.invalid",
                passwordEncoder.encode(Tokens.newToken()), now);
        sessions.invalidateAll(userId);
    }

    private void verifyPassword(User user, String password, String field) {
        if (!rateLimiter.tryConsume(RateLimiter.Policy.ACCOUNT_PASSWORD, user.getId().toString())) {
            throw ApiException.tooManyRequests();
        }
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.field(field, "La contraseña no es correcta.");
        }
    }

    private User require(UUID userId) {
        return users.findById(userId).filter(User::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                        "Tu sesión expiró. Ingresá nuevamente."));
    }

    private static UserAccount get(User user) {
        return new UserAccount(user.getEmail(), user.getFirstName(), user.getLastName(), user.isProductEmails());
    }
}
