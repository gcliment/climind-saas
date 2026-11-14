package com.climind.saas.auth;

import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.util.Masking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Crea el SUPER_ADMIN de Climind al arrancar si se configuró {@code ADMIN_BOOTSTRAP_EMAIL} y
 * {@code ADMIN_BOOTSTRAP_PASSWORD} y el usuario no existe. Nunca modifica un usuario existente
 * (cambiar la contraseña después no se pisa en cada reinicio). Tras el primer arranque en producción,
 * conviene quitar la contraseña de las variables de entorno.
 */
@Slf4j
@Component
public class SuperAdminBootstrap implements ApplicationRunner {

    private final AppProperties properties;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService audit;
    private final Clock clock;

    public SuperAdminBootstrap(AppProperties properties, UserRepository users, PasswordEncoder passwordEncoder,
                               AuditLogService audit, Clock clock) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = User.normalizeEmail(properties.getAdminBootstrap().getEmail());
        String password = properties.getAdminBootstrap().getPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (users.existsByEmail(email)) {
            return;
        }
        PasswordPolicy.validate(password, email).ifPresent(msg -> {
            throw new IllegalStateException("ADMIN_BOOTSTRAP_PASSWORD no cumple la política de contraseñas: " + msg);
        });
        Instant now = clock.instant();
        User admin = new User(email, passwordEncoder.encode(password), "Climind", "Admin", now);
        admin.promoteToSuperAdmin();
        admin.markEmailVerified(now);
        users.save(admin);
        audit.record(AuditAction.SUPER_ADMIN_BOOTSTRAPPED, admin.getId(), null);
        log.info("SUPER_ADMIN creado: {}", Masking.email(email));
    }
}
