-- =============================================================================
-- V4 — Cotizaciones (simulaciones), consultas de clientes e historial de seguimiento.
--
-- Simulación = un cliente calculó un precio con la versión publicada de un cotizador.
-- Consulta    = el cliente dejó sus datos para que el negocio lo contacte (máximo una por simulación).
-- Venta       = el negocio marca manualmente la consulta como CONVERTIDA. Nada se convierte solo.
--
-- Cada simulación guarda un snapshot completo (respuestas, desglose, total, aclaración y vigencia) y
-- referencia la versión inmutable usada: cambiar precios nunca altera cotizaciones anteriores.
-- =============================================================================

CREATE TABLE quote_simulations (
    id                    uuid          PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    calculator_id         uuid          NOT NULL REFERENCES calculators (id) ON DELETE RESTRICT,
    calculator_version_id uuid          NOT NULL REFERENCES calculator_versions (id) ON DELETE RESTRICT,
    -- Código legible para el cliente y el negocio (COT-XXXXXX). No da acceso por sí solo.
    code                  varchar(12)   NOT NULL,
    -- Capacidad de lectura de la cotización para el visitante (enlace privado). Ver docs/ARCHITECTURE.md.
    access_token          varchar(43)   NOT NULL,
    calculator_name       varchar(120)  NOT NULL,
    answers               jsonb         NOT NULL,
    result                jsonb         NOT NULL,
    total                 numeric(14,2) NOT NULL,
    currency              varchar(3)    NOT NULL,
    price_scale           smallint      NOT NULL,
    disclaimer            varchar(500),
    valid_until           date,
    -- Deduplicación: mismo visitante + misma versión + mismas respuestas en 30 minutos = misma simulación.
    answers_hash          varchar(64)   NOT NULL,
    visitor_hash          varchar(64),
    whatsapp_clicked_at   timestamptz,
    redacted_at           timestamptz,
    created_at            timestamptz   NOT NULL,
    CONSTRAINT uq_quote_simulations_code UNIQUE (code),
    CONSTRAINT ck_quote_simulations_code CHECK (code ~ '^COT-[A-Z0-9]{6}$'),
    CONSTRAINT ck_quote_simulations_total CHECK (total >= 0),
    CONSTRAINT ck_quote_simulations_scale CHECK (price_scale IN (0, 2))
);
CREATE INDEX ix_quote_simulations_tenant ON quote_simulations (tenant_id, created_at DESC);
CREATE INDEX ix_quote_simulations_calculator ON quote_simulations (tenant_id, calculator_id, created_at DESC);
CREATE INDEX ix_quote_simulations_dedup ON quote_simulations (calculator_version_id, visitor_hash, answers_hash, created_at DESC)
    WHERE visitor_hash IS NOT NULL;

-- El importe y la versión de una cotización emitida no cambian nunca. Solo se permite registrar el clic en
-- WhatsApp y borrar textos libres del cliente (anonimización).
CREATE FUNCTION quote_simulations_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.calculator_id IS DISTINCT FROM OLD.calculator_id
        OR NEW.calculator_version_id IS DISTINCT FROM OLD.calculator_version_id
        OR NEW.code IS DISTINCT FROM OLD.code
        OR NEW.total IS DISTINCT FROM OLD.total
        OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.price_scale IS DISTINCT FROM OLD.price_scale
        OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.result -> 'total' IS DISTINCT FROM OLD.result -> 'total'
        OR NEW.result -> 'lines' IS DISTINCT FROM OLD.result -> 'lines' THEN
        RAISE EXCEPTION 'quote_simulations: el importe de una cotización emitida es inmutable'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_quote_simulations_guard
    BEFORE UPDATE ON quote_simulations
    FOR EACH ROW EXECUTE FUNCTION quote_simulations_guard();

CREATE TABLE quote_requests (
    id                uuid          PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    simulation_id     uuid          NOT NULL REFERENCES quote_simulations (id) ON DELETE RESTRICT,
    calculator_id     uuid          NOT NULL REFERENCES calculators (id) ON DELETE RESTRICT,
    -- Copias de la simulación (inmutable) para listar, buscar y exportar sin joins.
    code              varchar(12)   NOT NULL,
    calculator_name   varchar(120)  NOT NULL,
    total             numeric(14,2) NOT NULL,
    currency          varchar(3)    NOT NULL,
    summary           varchar(300),
    customer_name     varchar(120),
    customer_whatsapp varchar(15),
    customer_email    varchar(254),
    comments          varchar(1000),
    consent_at        timestamptz   NOT NULL,
    status            varchar(20)   NOT NULL DEFAULT 'NUEVA',
    status_changed_at timestamptz   NOT NULL,
    anonymized_at     timestamptz,
    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    CONSTRAINT uq_quote_requests_simulation UNIQUE (simulation_id),
    CONSTRAINT ck_quote_requests_status
        CHECK (status IN ('NUEVA', 'CONTACTADA', 'EN_NEGOCIACION', 'CONVERTIDA', 'PERDIDA')),
    CONSTRAINT ck_quote_requests_whatsapp CHECK (customer_whatsapp IS NULL OR customer_whatsapp ~ '^[0-9]{8,15}$'),
    -- Sin datos de contacto solo si fue anonimizada.
    CONSTRAINT ck_quote_requests_contact
        CHECK (anonymized_at IS NOT NULL OR (customer_name IS NOT NULL AND customer_whatsapp IS NOT NULL))
);
CREATE INDEX ix_quote_requests_tenant ON quote_requests (tenant_id, created_at DESC);
CREATE INDEX ix_quote_requests_status ON quote_requests (tenant_id, status, created_at DESC);

-- Línea de tiempo de cada consulta: alta, cambios de estado y notas del negocio.
CREATE TABLE quote_status_history (
    id            uuid          PRIMARY KEY,
    tenant_id     uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    request_id    uuid          NOT NULL REFERENCES quote_requests (id) ON DELETE RESTRICT,
    kind          varchar(20)   NOT NULL,
    from_status   varchar(20),
    to_status     varchar(20),
    note          varchar(1000),
    actor_user_id uuid          REFERENCES users (id) ON DELETE RESTRICT,
    created_at    timestamptz   NOT NULL,
    CONSTRAINT ck_quote_status_history_kind CHECK (kind IN ('CREATED', 'STATUS_CHANGED', 'NOTE', 'ANONYMIZED'))
);
CREATE INDEX ix_quote_status_history_request ON quote_status_history (tenant_id, request_id, created_at);
