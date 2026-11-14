-- V6: comercialización (Fase 5).
--  * Negocio de demostración: datos ficticios para las demos públicas, separados de los clientes reales.
--  * Baja de cuenta a pedido del dueño: se programa con 30 días para arrepentirse y recién entonces se
--    eliminan los datos del negocio (los registros de pagos se conservan por obligaciones contables).
--  * Emails de producto (recordatorios y resumen mensual) con baja en un clic.

-- ---------------------------------------------------------------- negocios
ALTER TABLE tenants
    ADD COLUMN demo                   boolean      NOT NULL DEFAULT false,
    ADD COLUMN deletion_requested_at  timestamptz,
    ADD COLUMN deletion_scheduled_for timestamptz,
    ADD COLUMN deletion_requested_by  uuid         REFERENCES users (id) ON DELETE RESTRICT,
    ADD COLUMN deleted_at             timestamptz;

ALTER TABLE tenants DROP CONSTRAINT ck_tenants_status;
ALTER TABLE tenants ADD CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'));
ALTER TABLE tenants ADD CONSTRAINT ck_tenants_deletion
    CHECK ((deletion_scheduled_for IS NULL) = (deletion_requested_at IS NULL));
ALTER TABLE tenants ADD CONSTRAINT ck_tenants_deleted
    CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL));

CREATE INDEX ix_tenants_deletion_due ON tenants (deletion_scheduled_for)
    WHERE deletion_scheduled_for IS NOT NULL AND deleted_at IS NULL;

COMMENT ON COLUMN tenants.demo IS 'Negocio ficticio de las demos públicas: sin consultas, sin cupo y fuera de las métricas';
COMMENT ON COLUMN tenants.deletion_scheduled_for IS 'Baja pedida por el dueño: los datos se eliminan a partir de esta fecha';

-- ---------------------------------------------------------------- usuarios
ALTER TABLE users
    ADD COLUMN product_emails     boolean     NOT NULL DEFAULT true,
    ADD COLUMN email_prefs_token  varchar(64),
    ADD COLUMN deleted_at         timestamptz;

CREATE UNIQUE INDEX ux_users_email_prefs_token ON users (email_prefs_token) WHERE email_prefs_token IS NOT NULL;

COMMENT ON COLUMN users.product_emails IS 'Acepta emails de producto (consejos y resumen mensual). Los transaccionales se envían siempre';
COMMENT ON COLUMN users.email_prefs_token IS 'Token del enlace de baja de emails de producto (solo permite darse de baja)';

-- ---------------------------------------------------------------- envíos de emails de producto
-- Una fila por (usuario, campaña, período): garantiza que un recordatorio o un resumen se mande una sola vez
-- aunque el job corra dos veces o en dos instancias.
CREATE TABLE product_email_sends (
    user_id     uuid         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    campaign    varchar(40)  NOT NULL,
    period_key  varchar(20)  NOT NULL,
    sent_at     timestamptz  NOT NULL,
    PRIMARY KEY (user_id, campaign, period_key)
);

-- ---------------------------------------------------------------- versiones inmutables (excepción de la baja)
-- Las versiones publicadas siguen siendo inmutables. Única excepción: borrarlas (nunca modificarlas) cuando el
-- dueño pidió la baja de la cuenta y el proceso de eliminación está en curso.
CREATE OR REPLACE FUNCTION calculator_versions_immutable() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND EXISTS (
            SELECT 1 FROM tenants t
            WHERE t.id = OLD.tenant_id AND t.deletion_requested_at IS NOT NULL AND t.deleted_at IS NULL) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'calculator_versions es inmutable (% no permitido)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;
