package com.climind.saas.auth;

import com.climind.saas.businesses.BusinessService;
import com.climind.saas.notifications.EmailService;
import com.climind.saas.notifications.MailTemplate;
import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.ratelimit.RateLimiter;
import com.climind.saas.shared.security.AppPrincipal;
import com.climind.saas.shared.util.Masking;
import com.climind.saas.shared.util.Tokens;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.tenants.Tenant;
import com.climind.saas.tenants.TenantMembership;
import com.climind.saas.tenants.TenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registro, verificación de email, login y recuperación de contraseña.
 * <p>
 * Principios: mensajes que no revelan si un email está registrado (registro, recuperación y reenvío
 * responden siempre igual), tokens de un solo uso guardados como hash, bloqueo progresivo ante
 * intentos fallidos y ningún dato sensible en logs.
 */
@Slf4j
@Service
public class AuthService {

    static final String INVALID_CREDENTIALS_MESSAGE = "El email o la contraseña no son correctos.";

    private final UserRepository users;
    private final EmailVerificationTokenRepository verificationTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final TenantService tenantService;
    private final BusinessService businessService;
    private final EmailService emailService;
    private final AuditLogService audit;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final SessionService sessionService;
    private final AppProperties properties;
    private final Clock clock;
    /** Hash ficticio para igualar el tiempo de respuesta cuando el email no existe. */
    private final String dummyHash;

    public AuthService(UserRepository users, EmailVerificationTokenRepository verificationTokens,
                       PasswordResetTokenRepository resetTokens, TenantService tenantService,
                       BusinessService businessService, EmailService emailService, AuditLogService audit,
                       PasswordEncoder passwordEncoder, RateLimiter rateLimiter, SessionService sessionService,
                       AppProperties properties, Clock clock) {
        this.users = users;
        this.verificationTokens = verificationTokens;
        this.resetTokens = resetTokens;
        this.tenantService = tenantService;
        this.businessService = businessService;
        this.emailService = emailService;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.sessionService = sessionService;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    // ------------------------------------------------------------------ registro

    /**
     * Crea usuario + negocio + membresía OWNER + perfil, y envía el email de verificación.
     * Si el email ya existe no lo revela: envía un aviso a esa casilla y responde igual.
     */
    @Transactional
    public void register(AuthDtos.RegisterRequest request) {
        String email = User.normalizeEmail(request.email());
        if (!request.password().equals(request.passwordConfirmation())) {
            throw ApiException.field("passwordConfirmation", "Las contraseñas no coinciden.");
        }
        PasswordPolicy.validate(request.password(), email)
                .ifPresent(msg -> {
                    throw ApiException.field("password", msg);
                });

        Optional<User> existing = users.findByEmail(email);
        if (existing.isPresent()) {
            passwordEncoder.encode(request.password()); // mismo costo que un alta: no revela el email por tiempos
            if (rateLimiter.tryConsume(RateLimiter.Policy.EMAIL_ACTION, email)) {
                String base = properties.publicUrlWithoutTrailingSlash();
                emailService.send(MailTemplate.ACCOUNT_EXISTS, email,
                        Map.of("loginLink", base + "/ingresar", "resetLink", base + "/recuperar"),
                        existing.get().getId(), null);
            }
            log.info("Registro con email existente {}", Masking.email(email));
            return;
        }

        Instant now = clock.instant();
        User user = users.save(new User(email, passwordEncoder.encode(request.password()),
                request.firstName(), request.lastName(), now));
        String businessName = "Negocio de " + user.getFirstName();
        Tenant tenant = tenantService.createWorkspace(user.getId(), businessName);
        businessService.initialize(tenant.getId(), businessName, email);
        audit.record(AuditAction.USER_REGISTERED, user.getId(), tenant.getId(), "user", user.getId().toString(),
                Map.of());
        if (rateLimiter.tryConsume(RateLimiter.Policy.EMAIL_ACTION, email)) {
            sendVerification(user, now);
        }
    }

    @Transactional
    public void resendVerification(String rawEmail) {
        String email = User.normalizeEmail(rawEmail);
        users.findByEmail(email)
                .filter(u -> !u.isEmailVerified() && u.isActive())
                .filter(u -> rateLimiter.tryConsume(RateLimiter.Policy.EMAIL_ACTION, email))
                .ifPresent(u -> sendVerification(u, clock.instant()));
    }

    private void sendVerification(User user, Instant now) {
        verificationTokens.invalidateAll(user.getId(), now);
        String token = Tokens.newToken();
        verificationTokens.save(new EmailVerificationToken(user.getId(), Tokens.sha256Hex(token), now,
                now.plus(properties.getSecurity().getVerificationTokenTtl())));
        emailService.send(MailTemplate.VERIFY_EMAIL, user.getEmail(),
                Map.of("firstName", user.getFirstName(),
                        "link", properties.publicUrlWithoutTrailingSlash() + "/verificar?token=" + token),
                user.getId(), null);
    }

    @Transactional
    public void verifyEmail(String token) {
        EmailVerificationToken stored = findToken(token, verificationTokens::findByTokenHash);
        User user = users.findById(stored.getUserId()).orElseThrow(AuthService::invalidLink);
        Instant now = clock.instant();
        if (!stored.isUsable(now)) {
            if (user.isEmailVerified()) {
                return; // enlace ya usado y cuenta verificada: idempotente
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "LINK_EXPIRED",
                    "El enlace venció o ya fue usado. Pedí uno nuevo desde la pantalla de ingreso.");
        }
        stored.markUsed(now);
        boolean firstVerification = !user.isEmailVerified();
        user.markEmailVerified(now);
        if (firstVerification) {
            audit.record(AuditAction.EMAIL_VERIFIED, user.getId(), null);
            emailService.send(MailTemplate.WELCOME, user.getEmail(),
                    Map.of("firstName", user.getFirstName(),
                            "appLink", properties.publicUrlWithoutTrailingSlash() + "/app/"),
                    user.getId(), null);
        }
    }

    // ------------------------------------------------------------------ login

    /**
     * Valida credenciales y devuelve el principal para la sesión. No hace rollback ante errores de
     * negocio: los intentos fallidos y bloqueos deben persistirse.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AppPrincipal authenticate(AuthDtos.LoginRequest request) {
        String email = User.normalizeEmail(request.email());
        if (!rateLimiter.tryConsume(RateLimiter.Policy.LOGIN_EMAIL, email)) {
            throw ApiException.tooManyRequests();
        }
        Optional<User> found = users.findByEmail(email);
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash);
            audit.record(AuditAction.LOGIN_FAILED, null, null, "email", null, Map.of("reason", "unknown_email"));
            throw invalidCredentials();
        }
        User user = found.get();
        Instant now = clock.instant();
        if (user.isLocked(now)) {
            long minutes = Math.max(1, Duration.between(now, user.getLockedUntil()).toMinutes() + 1);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED",
                    "Por seguridad bloqueamos el ingreso por " + minutes + " min. "
                            + "Probá de nuevo más tarde o restablecé tu contraseña.");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            boolean locked = user.registerFailedLogin(now, properties.getSecurity().getLoginMaxFailures(),
                    properties.getSecurity().getLoginLockDuration());
            audit.record(locked ? AuditAction.ACCOUNT_LOCKED : AuditAction.LOGIN_FAILED, user.getId(), null,
                    "user", user.getId().toString(), Map.of("reason", "bad_password",
                            "failures", user.getFailedLoginCount()));
            throw invalidCredentials();
        }
        if (!user.isActive()) {
            throw ApiException.forbidden("ACCOUNT_DISABLED",
                    "Tu cuenta está deshabilitada. Escribinos para resolverlo.");
        }
        if (!user.isEmailVerified()) {
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED",
                    "Todavía no confirmaste tu email. Revisá tu casilla o pedí un nuevo enlace.");
        }
        user.registerSuccessfulLogin(now);
        Optional<TenantMembership> membership = tenantService.primaryMembership(user.getId());
        audit.record(AuditAction.LOGIN_SUCCEEDED, user.getId(), membership.map(TenantMembership::getTenantId).orElse(null));
        return new AppPrincipal(user.getId(), user.getEmail(),
                membership.map(TenantMembership::getTenantId).orElse(null),
                membership.map(m -> m.getRole().name()).orElse(null),
                user.getPlatformRole());
    }

    // ------------------------------------------------------------------ recuperación

    @Transactional
    public void requestPasswordReset(String rawEmail) {
        String email = User.normalizeEmail(rawEmail);
        Optional<User> found = users.findByEmail(email).filter(User::isActive);
        if (found.isEmpty() || !rateLimiter.tryConsume(RateLimiter.Policy.EMAIL_ACTION, email)) {
            return;
        }
        User user = found.get();
        Instant now = clock.instant();
        resetTokens.invalidateAll(user.getId(), now);
        String token = Tokens.newToken();
        resetTokens.save(new PasswordResetToken(user.getId(), Tokens.sha256Hex(token), now,
                now.plus(properties.getSecurity().getResetTokenTtl())));
        audit.record(AuditAction.PASSWORD_RESET_REQUESTED, user.getId(), null);
        emailService.send(MailTemplate.PASSWORD_RESET, user.getEmail(),
                Map.of("firstName", user.getFirstName(),
                        "link", properties.publicUrlWithoutTrailingSlash() + "/restablecer?token=" + token),
                user.getId(), null);
    }

    @Transactional
    public void resetPassword(AuthDtos.ResetPasswordRequest request) {
        PasswordResetToken stored = findToken(request.token(), resetTokens::findByTokenHash);
        Instant now = clock.instant();
        if (!stored.isUsable(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LINK_EXPIRED",
                    "El enlace venció o ya fue usado. Pedí uno nuevo.");
        }
        User user = users.findById(stored.getUserId()).filter(User::isActive).orElseThrow(AuthService::invalidLink);
        if (!request.password().equals(request.passwordConfirmation())) {
            throw ApiException.field("passwordConfirmation", "Las contraseñas no coinciden.");
        }
        PasswordPolicy.validate(request.password(), user.getEmail())
                .ifPresent(msg -> {
                    throw ApiException.field("password", msg);
                });
        user.changePassword(passwordEncoder.encode(request.password()), now);
        // Quien recibió el enlace demostró ser dueño de la casilla.
        user.markEmailVerified(now);
        stored.markUsed(now);
        resetTokens.invalidateAll(user.getId(), now);
        audit.record(AuditAction.PASSWORD_RESET_COMPLETED, user.getId(), null);
        UUID userId = user.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionService.invalidateAll(userId);
            }
        });
        emailService.send(MailTemplate.PASSWORD_CHANGED, user.getEmail(),
                Map.of("firstName", user.getFirstName(),
                        "resetLink", properties.publicUrlWithoutTrailingSlash() + "/recuperar"),
                user.getId(), null);
    }

    // ------------------------------------------------------------------ usuario actual

    @Transactional(readOnly = true)
    public AuthDtos.MeResponse me(AppPrincipal principal) {
        User user = users.findById(principal.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                        "Tu sesión expiró. Ingresá nuevamente."));
        AuthDtos.TenantView tenant = principal.tenantId() == null ? null
                : tenantService.find(principal.tenantId())
                .map(t -> new AuthDtos.TenantView(t.getId(), t.getName(), t.getSlug(), principal.tenantRole(),
                        t.getStatus().name(), t.getOnboardingCompletedAt() != null,
                        t.isDeletionScheduled() ? t.getDeletionScheduledFor() : null))
                .orElse(null);
        return new AuthDtos.MeResponse(new AuthDtos.UserView(user.getId(), user.getEmail(), user.getFirstName(),
                user.getLastName(), user.getPlatformRole()), tenant);
    }

    // ------------------------------------------------------------------ helpers

    private static <T extends OneTimeToken> T findToken(String token,
                                                        java.util.function.Function<String, Optional<T>> finder) {
        if (!Tokens.looksValid(token)) {
            throw invalidLink();
        }
        return finder.apply(Tokens.sha256Hex(token)).orElseThrow(AuthService::invalidLink);
    }

    private static ApiException invalidLink() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LINK",
                "El enlace no es válido. Revisá que esté completo o pedí uno nuevo.");
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", INVALID_CREDENTIALS_MESSAGE);
    }
}
