package com.climind.saas.tenants;

import com.climind.saas.shared.security.AppPrincipal;
import com.climind.saas.shared.security.CurrentUser;
import com.climind.saas.shared.web.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resuelve el negocio (tenant) del request a partir de la sesión autenticada.
 * <p>
 * <b>Regla de aislamiento:</b> los servicios privados obtienen el tenant SIEMPRE de acá, nunca de un
 * parámetro, path o body enviado por el cliente. Además verifica que el negocio no esté suspendido.
 */
@Component
public class CurrentTenant {

    private final TenantRepository tenants;

    public CurrentTenant(TenantRepository tenants) {
        this.tenants = tenants;
    }

    /** Id del negocio activo; 403 si el usuario no tiene negocio o el negocio está suspendido. */
    @Transactional(readOnly = true)
    public UUID requireId() {
        AppPrincipal principal = CurrentUser.require();
        if (principal.tenantId() == null) {
            throw ApiException.forbidden("NO_TENANT", "Tu usuario no tiene un negocio asociado.");
        }
        Tenant tenant = tenants.findById(principal.tenantId())
                .orElseThrow(() -> ApiException.forbidden("NO_TENANT", "Tu usuario no tiene un negocio asociado."));
        if (!tenant.isActive()) {
            throw ApiException.forbidden("TENANT_SUSPENDED",
                    "Tu cuenta está suspendida. Escribinos para resolverlo.");
        }
        return tenant.getId();
    }
}
