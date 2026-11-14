package com.climind.saas.shared.security;

import org.springframework.security.core.AuthenticatedPrincipal;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * Usuario autenticado guardado en la sesión. Es deliberadamente mínimo (se serializa en la tabla de sesiones).
 * {@link #getName()} devuelve el id del usuario: Spring Session lo indexa para poder invalidar todas
 * las sesiones de un usuario (cambio de contraseña, suspensión).
 *
 * @param tenantId     negocio activo; {@code null} para el super admin sin negocio propio
 * @param tenantRole   OWNER | MEMBER; {@code null} si no hay negocio activo
 * @param platformRole SUPER_ADMIN | {@code null}
 */
public record AppPrincipal(UUID userId, String email, UUID tenantId, String tenantRole, String platformRole)
        implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    @Override
    public String getName() {
        return userId.toString();
    }

    public boolean isSuperAdmin() {
        return ROLE_SUPER_ADMIN.equals(platformRole);
    }
}
