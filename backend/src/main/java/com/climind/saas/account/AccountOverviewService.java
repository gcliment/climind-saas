package com.climind.saas.account;

import com.climind.saas.auth.AccountSettingsService;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.security.AppPrincipal;
import com.climind.saas.shared.web.ApiException;
import com.climind.saas.tenants.Tenant;
import com.climind.saas.tenants.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Resumen de la cuenta para la página "Tu cuenta" (usuario de la sesión y, si tiene, su negocio). */
@Service
public class AccountOverviewService {

    private final AccountSettingsService settings;
    private final TenantService tenants;
    private final AppProperties properties;

    public AccountOverviewService(AccountSettingsService settings, TenantService tenants, AppProperties properties) {
        this.settings = settings;
        this.tenants = tenants;
        this.properties = properties;
    }

    /**
     * @param owner                si el usuario es dueño del negocio (puede exportar y dar de baja la cuenta)
     * @param deletionScheduledFor baja programada (null si no hay una en curso)
     * @param deletionGraceDays    plazo para arrepentirse de una baja
     */
    public record AccountView(String email, String firstName, String lastName, boolean productEmails, boolean owner,
                              String businessName, Instant deletionScheduledFor, long deletionGraceDays) {
    }

    @Transactional(readOnly = true)
    public AccountView view(AppPrincipal principal) {
        AccountSettingsService.UserAccount user = settings.get(principal.userId());
        Tenant tenant = principal.tenantId() == null ? null : tenants.find(principal.tenantId()).orElse(null);
        if (tenant != null && tenant.getStatus() == Tenant.Status.DELETED) {
            throw ApiException.forbidden("TENANT_DELETED", "Esta cuenta fue eliminada.");
        }
        boolean owner = tenant != null && "OWNER".equals(principal.tenantRole());
        return new AccountView(user.email(), user.firstName(), user.lastName(), user.productEmails(), owner,
                tenant == null ? null : tenant.getName(),
                tenant != null && tenant.isDeletionScheduled() ? tenant.getDeletionScheduledFor() : null,
                properties.getAccount().getDeletionGrace().toDays());
    }
}
