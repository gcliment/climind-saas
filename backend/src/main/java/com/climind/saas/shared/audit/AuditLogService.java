package com.climind.saas.shared.audit;

import com.climind.saas.shared.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Registro de auditoría (tabla {@code audit_logs}). Nunca incluir contraseñas, tokens ni datos de pago en
 * {@code metadata}. Participa de la transacción del llamador: si la operación se revierte, el registro también.
 */
@Slf4j
@Service
public class AuditLogService {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final ClientIpResolver ipResolver;
    private final Clock clock;

    public AuditLogService(JdbcTemplate jdbc, JsonMapper jsonMapper, ClientIpResolver ipResolver, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.ipResolver = ipResolver;
        this.clock = clock;
    }

    public void record(AuditAction action, UUID actorUserId, UUID tenantId, String targetType, String targetId,
                       Map<String, ?> metadata) {
        jdbc.update("""
                        INSERT INTO audit_logs (occurred_at, actor_user_id, tenant_id, action, target_type, target_id, ip, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        """,
                Timestamp.from(clock.instant()), actorUserId, tenantId, action.name(), targetType, targetId,
                currentIp(), jsonMapper.writeValueAsString(metadata == null ? Map.of() : metadata));
    }

    public void record(AuditAction action, UUID actorUserId, UUID tenantId) {
        record(action, actorUserId, tenantId, null, null, Map.of());
    }

    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return ipResolver.resolve(request);
        }
        return null;
    }
}
