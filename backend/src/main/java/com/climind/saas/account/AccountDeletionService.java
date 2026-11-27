package com.climind.saas.account;

import com.climind.saas.auth.AccountSettingsService;
import com.climind.saas.notifications.EmailService;
import com.climind.saas.notifications.MailTemplate;
import com.climind.saas.shared.audit.AuditAction;
import com.climind.saas.shared.audit.AuditLogService;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.storage.FileStorage;
import com.climind.saas.subscriptions.BillingService;
import com.climind.saas.tenants.Tenant;
import com.climind.saas.tenants.TenantMembership;
import com.climind.saas.tenants.TenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Baja de la cuenta, SIEMPRE a pedido del dueño (nunca automática por inactividad ni por falta de pago).
 * <ol>
 *   <li>Pedido: confirma con su contraseña y escribiendo ELIMINAR. Se cancela el plan pago en el proveedor (no
 *       hay más cobros) y los cotizadores dejan de atender al público.</li>
 *   <li>Plazo para arrepentirse (30 días por defecto): el panel sigue accesible para descargar los datos o
 *       cancelar la baja.</li>
 *   <li>Ejecución (job nocturno): se eliminan los datos del negocio (cotizadores, versiones, cotizaciones,
 *       consultas, perfil, logo) y se anonimiza el usuario. Se conservan los registros de suscripciones y
 *       pagos (obligaciones contables) y la auditoría, sin datos de contacto.</li>
 * </ol>
 */
@Slf4j
@Service
public class AccountDeletionService {

    static final String CONFIRMATION_WORD = "ELIMINAR";
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy",
            Locale.forLanguageTag("es-AR"));

    private final TenantService tenants;
    private final AccountSettingsService settings;
    private final BillingService billing;
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final EmailService emails;
    private final AuditLogService audit;
    private final AppProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AccountDeletionService(TenantService tenants, AccountSettingsService settings, BillingService billing,
                                  JdbcTemplate jdbc, FileStorage storage, EmailService emails, AuditLogService audit,
                                  AppProperties properties, TransactionTemplate transactions, Clock clock) {
        this.tenants = tenants;
        this.settings = settings;
        this.billing = billing;
        this.jdbc = jdbc;
        this.storage = storage;
        this.emails = emails;
        this.audit = audit;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Estado de la baja para el panel. */
    public record DeletionStatus(Instant scheduledFor, boolean canceledPaidPlan) {
    }

    @Transactional
    public DeletionStatus schedule(UUID tenantId, UUID userId, String password, String confirmation) {
        settings.confirmPassword(userId, password, "password");
        if (confirmation == null || !CONFIRMATION_WORD.equals(confirmation.strip().toUpperCase(Locale.ROOT))) {
            throw ApiException.field("confirmation", "Escribí ELIMINAR para confirmar la baja.");
        }
        Tenant tenant = tenants.find(tenantId).orElseThrow();
        if (tenant.isDeletionScheduled()) {
            return new DeletionStatus(tenant.getDeletionScheduledFor(), false);
        }
        // Primero el proveedor de pagos: si falla, no se programa nada y el dueño puede reintentar.
        boolean canceledPaidPlan = billing.stopBillingForAccountDeletion(tenantId, userId);
        Instant now = clock.instant();
        Instant scheduledFor = now.plus(properties.getAccount().getDeletionGrace());
        tenants.scheduleDeletion(tenantId, userId, now, scheduledFor);
        audit.record(AuditAction.ACCOUNT_DELETION_SCHEDULED, userId, tenantId, "tenant", tenantId.toString(),
                Map.of("scheduledFor", scheduledFor.toString()));
        AccountSettingsService.UserAccount user = settings.get(userId);
        emails.send(MailTemplate.ACCOUNT_DELETION_SCHEDULED, user.email(), Map.of(
                "firstName", user.firstName(),
                "businessName", businessName(tenantId, tenant.getName()),
                "scheduledFor", LONG_DATE.format(scheduledFor.atZone(ZONE)),
                "canceledPaidPlan", canceledPaidPlan,
                "link", properties.publicUrlWithoutTrailingSlash() + "/app/cuenta"), userId, tenantId);
        return new DeletionStatus(scheduledFor, canceledPaidPlan);
    }

    /** El dueño se arrepiente: todo vuelve a la normalidad, salvo el plan pago (que ya se canceló). */
    @Transactional
    public void cancel(UUID tenantId, UUID userId) {
        Tenant tenant = tenants.find(tenantId).orElseThrow();
        if (!tenant.isDeletionScheduled()) {
            throw ApiException.conflict("NO_DELETION", "Tu cuenta no tiene una baja programada.");
        }
        tenants.cancelDeletion(tenantId);
        audit.record(AuditAction.ACCOUNT_DELETION_CANCELED, userId, tenantId);
    }

    /**
     * Eliminación definitiva desde el panel de Climind (SUPER_ADMIN): sin plazo para arrepentirse. Cancela el plan
     * pago en el proveedor, borra los datos del negocio y anonimiza a sus usuarios (el email queda libre para
     * registrarse de nuevo). No aplica a la demo ni a negocios con un SUPER_ADMIN.
     */
    @Transactional
    public void deleteNowByAdmin(UUID tenantId, UUID adminUserId, boolean notifyOwner) {
        Tenant tenant = tenants.find(tenantId).orElseThrow(() -> ApiException.notFound("No existe ese negocio."));
        if (tenant.isDemo()) {
            throw ApiException.conflict("DEMO_TENANT", "La demo pública no se puede eliminar.");
        }
        if (tenant.getStatus() == Tenant.Status.DELETED) {
            return;
        }
        Integer admins = jdbc.queryForObject("""
                SELECT count(*) FROM tenant_memberships m JOIN users u ON u.id = m.user_id
                WHERE m.tenant_id = ? AND u.platform_role = 'SUPER_ADMIN'
                """, Integer.class, tenantId);
        if (admins != null && admins > 0) {
            throw ApiException.conflict("ADMIN_TENANT", "No se puede eliminar un negocio de un administrador de Climind.");
        }
        billing.stopBillingForAccountDeletion(tenantId, adminUserId);
        Instant now = clock.instant();
        if (tenant.isDeletionScheduled()) {
            tenants.cancelDeletion(tenantId);
        }
        tenants.scheduleDeletion(tenantId, adminUserId, now, now);
        audit.record(AuditAction.ACCOUNT_DELETED_BY_ADMIN, adminUserId, tenantId, "tenant", tenantId.toString(),
                Map.of("notifyOwner", notifyOwner));
        execute(tenantId, notifyOwner);
    }

    /** Ejecuta las bajas cuyo plazo venció. Cada negocio en su propia transacción. */
    @Scheduled(cron = "${app.account.deletion-cron:0 10 4 * * *}", zone = "America/Argentina/Buenos_Aires")
    public int runDue() {
        List<UUID> due = tenants.deletionsDue(clock.instant());
        int done = 0;
        for (UUID tenantId : due) {
            try {
                transactions.executeWithoutResult(status -> execute(tenantId));
                done++;
            } catch (RuntimeException e) {
                log.error("No se pudo completar la baja del negocio {}", tenantId, e);
            }
        }
        if (done > 0) {
            log.info("Bajas de cuenta ejecutadas: {}", done);
        }
        return done;
    }

    /** Elimina los datos del negocio. Debe correr dentro de una transacción (todo o nada). */
    void execute(UUID tenantId) {
        execute(tenantId, true);
    }

    private void execute(UUID tenantId, boolean notifyMembers) {
        Tenant tenant = tenants.find(tenantId).orElseThrow();
        if (!tenant.isDeletionScheduled() || tenant.getDeletionScheduledFor().isAfter(clock.instant())) {
            return; // se canceló o todavía no venció
        }
        Instant now = clock.instant();
        String businessName = businessName(tenantId, tenant.getName());
        List<UUID> members = tenants.members(tenantId).stream().map(TenantMembership::getUserId).toList();
        List<String> storageKeys = jdbc.queryForList("SELECT storage_key FROM business_assets WHERE tenant_id = ?",
                String.class, tenantId);

        // Datos del negocio y de sus clientes. El orden respeta las claves foráneas.
        jdbc.update("DELETE FROM quote_status_history WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM quote_requests WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM quote_simulations WHERE tenant_id = ?", tenantId);
        jdbc.update("""
                UPDATE calculators SET status = 'ARCHIVADO', published_version_id = NULL,
                       archived_at = coalesce(archived_at, ?) WHERE tenant_id = ?
                """, java.sql.Timestamp.from(now), tenantId);
        // Permitido por el trigger de versiones solo porque la baja está en curso (ver V6).
        jdbc.update("DELETE FROM calculator_versions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM calculators WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM usage_counters WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE business_profiles SET logo_asset_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM business_assets WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM business_profiles WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM business_settings WHERE tenant_id = ?", tenantId);
        // Registros que se conservan, sin datos de contacto.
        jdbc.update("UPDATE email_delivery_logs SET recipient = 'eliminado' WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE audit_logs SET ip = NULL WHERE tenant_id = ?", tenantId);

        // Personas: se avisa antes de anonimizar (el email sale después del commit, con la dirección guardada).
        for (UUID userId : members) {
            AccountSettingsService.UserAccount user = settings.get(userId);
            if (notifyMembers) {
                emails.send(MailTemplate.ACCOUNT_DELETED, user.email(),
                        Map.of("firstName", user.firstName(), "businessName", businessName), null, null);
            }
            jdbc.update("UPDATE email_delivery_logs SET recipient = 'eliminado' WHERE user_id = ?", userId);
            jdbc.update("UPDATE audit_logs SET ip = NULL WHERE actor_user_id = ?", userId);
            jdbc.update("DELETE FROM email_verification_tokens WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM password_reset_tokens WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM product_email_sends WHERE user_id = ?", userId);
            settings.anonymize(userId);
        }
        tenants.markDeleted(tenantId, now);
        audit.record(AuditAction.ACCOUNT_DELETED, null, tenantId, "tenant", tenantId.toString(),
                Map.of("users", members.size()));

        // Los archivos se borran recién cuando la transacción confirmó (si algo falla, no se pierden).
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storageKeys.forEach(key -> {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("No se pudo borrar el archivo {} del negocio eliminado {}", key, tenantId);
                    }
                });
            }
        });
    }

    private String businessName(UUID tenantId, String fallback) {
        return jdbc.queryForList("SELECT business_name FROM business_profiles WHERE tenant_id = ?", String.class,
                tenantId).stream().findFirst().orElse(fallback);
    }
}
