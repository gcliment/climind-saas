-- =============================================================================
-- V5 — Planes, suscripciones, pagos, eventos del proveedor de pagos y contadores de uso.
--
-- Reglas (docs/BUSINESS_RULES.md, "Planes y cuotas"):
--  * Los planes y sus límites viven en la base y se editan desde el panel de administración.
--  * Una suscripción paga guarda una COPIA del precio y los límites contratados: editar un plan no cambia
--    contratos vigentes. El plan gratuito se aplica siempre con sus valores actuales.
--  * Un plan pago se habilita solo cuando el proveedor confirma un pago aprobado (servidor a servidor).
--  * Nunca se borran datos por vencimiento, cancelación o pago fallido.
-- =============================================================================

CREATE TABLE subscription_plans (
    id                         uuid          PRIMARY KEY,
    code                       varchar(30)   NOT NULL,
    name                       varchar(60)   NOT NULL,
    description                varchar(300),
    price                      numeric(14,2) NOT NULL DEFAULT 0,
    currency                   varchar(3)    NOT NULL DEFAULT 'ARS',
    billing_period             varchar(10)   NOT NULL DEFAULT 'MONTHLY',
    is_free                    boolean       NOT NULL DEFAULT false,
    max_published_calculators  integer       NOT NULL,
    max_monthly_quotes         integer       NOT NULL,
    csv_export                 boolean       NOT NULL DEFAULT false,
    custom_branding            boolean       NOT NULL DEFAULT false,
    lead_email_notifications   boolean       NOT NULL DEFAULT true,
    active_for_new             boolean       NOT NULL DEFAULT true,
    sort_order                 integer       NOT NULL DEFAULT 0,
    lock_version               integer       NOT NULL DEFAULT 0,
    created_at                 timestamptz   NOT NULL,
    updated_at                 timestamptz   NOT NULL,
    CONSTRAINT uq_subscription_plans_code UNIQUE (code),
    CONSTRAINT ck_subscription_plans_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT ck_subscription_plans_period CHECK (billing_period IN ('MONTHLY', 'YEARLY')),
    CONSTRAINT ck_subscription_plans_price CHECK (price >= 0 AND (is_free OR price > 0)),
    CONSTRAINT ck_subscription_plans_limits CHECK (max_published_calculators BETWEEN 1 AND 1000
        AND max_monthly_quotes BETWEEN 1 AND 10000000)
);
-- Un solo plan gratuito (el que se aplica a todo negocio sin suscripción paga vigente).
CREATE UNIQUE INDEX uq_subscription_plans_free ON subscription_plans (is_free) WHERE is_free;

CREATE TABLE tenant_subscriptions (
    id                         uuid          PRIMARY KEY,
    tenant_id                  uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    plan_id                    uuid          NOT NULL REFERENCES subscription_plans (id) ON DELETE RESTRICT,
    status                     varchar(20)   NOT NULL,
    provider                   varchar(20)   NOT NULL,
    external_id                varchar(80),
    checkout_url               varchar(500),
    -- Copia de lo contratado (no cambia aunque se edite el plan).
    plan_code                  varchar(30)   NOT NULL,
    plan_name                  varchar(60)   NOT NULL,
    price                      numeric(14,2) NOT NULL,
    currency                   varchar(3)    NOT NULL,
    billing_period             varchar(10)   NOT NULL,
    max_published_calculators  integer       NOT NULL,
    max_monthly_quotes         integer       NOT NULL,
    csv_export                 boolean       NOT NULL,
    custom_branding            boolean       NOT NULL,
    lead_email_notifications   boolean       NOT NULL,
    -- Estado del cobro.
    provider_status            varchar(30),
    current_period_start       timestamptz,
    current_period_end         timestamptz,
    grace_until                timestamptz,
    last_payment_at            timestamptz,
    canceled_at                timestamptz,
    ended_at                   timestamptz,
    end_reason                 varchar(40),
    last_synced_at             timestamptz,
    lock_version               integer       NOT NULL DEFAULT 0,
    created_at                 timestamptz   NOT NULL,
    updated_at                 timestamptz   NOT NULL,
    CONSTRAINT ck_tenant_subscriptions_status
        CHECK (status IN ('PENDING', 'TRIALING', 'ACTIVE', 'PAST_DUE', 'PAUSED', 'CANCELED', 'EXPIRED')),
    CONSTRAINT ck_tenant_subscriptions_provider CHECK (provider IN ('MERCADOPAGO', 'FAKE')),
    CONSTRAINT ck_tenant_subscriptions_price CHECK (price > 0)
);
CREATE UNIQUE INDEX uq_tenant_subscriptions_external ON tenant_subscriptions (provider, external_id)
    WHERE external_id IS NOT NULL;
-- A lo sumo una suscripción vigente y un checkout pendiente por negocio.
CREATE UNIQUE INDEX uq_tenant_subscriptions_current ON tenant_subscriptions (tenant_id)
    WHERE status IN ('ACTIVE', 'PAST_DUE', 'PAUSED', 'TRIALING');
CREATE UNIQUE INDEX uq_tenant_subscriptions_pending ON tenant_subscriptions (tenant_id) WHERE status = 'PENDING';
CREATE INDEX ix_tenant_subscriptions_tenant ON tenant_subscriptions (tenant_id, created_at DESC);
CREATE INDEX ix_tenant_subscriptions_status ON tenant_subscriptions (status, current_period_end);

CREATE TABLE payment_transactions (
    id              uuid          PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    subscription_id uuid          NOT NULL REFERENCES tenant_subscriptions (id) ON DELETE RESTRICT,
    provider        varchar(20)   NOT NULL,
    external_id     varchar(80)   NOT NULL,
    status          varchar(20)   NOT NULL,
    provider_status varchar(40),
    amount          numeric(14,2) NOT NULL,
    currency        varchar(3)    NOT NULL,
    charge_date     timestamptz,
    paid_at         timestamptz,
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    -- Un pago del proveedor se registra una sola vez (idempotencia ante webhooks repetidos).
    CONSTRAINT uq_payment_transactions_external UNIQUE (provider, external_id),
    CONSTRAINT ck_payment_transactions_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REFUNDED', 'CANCELLED')),
    CONSTRAINT ck_payment_transactions_amount CHECK (amount >= 0)
);
CREATE INDEX ix_payment_transactions_tenant ON payment_transactions (tenant_id, created_at DESC);
CREATE INDEX ix_payment_transactions_status ON payment_transactions (status, created_at DESC);

-- Notificaciones recibidas del proveedor y correcciones de la conciliación. event_key único = idempotencia.
CREATE TABLE payment_provider_events (
    id           uuid         PRIMARY KEY,
    provider     varchar(20)  NOT NULL,
    event_key    varchar(120) NOT NULL,
    event_type   varchar(60)  NOT NULL,
    resource_id  varchar(80),
    payload      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    result       varchar(30)  NOT NULL,
    detail       varchar(500),
    received_at  timestamptz  NOT NULL,
    CONSTRAINT uq_payment_provider_events_key UNIQUE (provider, event_key),
    CONSTRAINT ck_payment_provider_events_result
        CHECK (result IN ('PROCESSED', 'IGNORED', 'FAILED', 'CORRECTED'))
);
CREATE INDEX ix_payment_provider_events_received ON payment_provider_events (received_at DESC);

-- Consumo por período de facturación. El incremento es atómico y condicionado al límite.
CREATE TABLE usage_counters (
    tenant_id    uuid        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    metric       varchar(30) NOT NULL,
    period_start date        NOT NULL,
    count        integer     NOT NULL DEFAULT 0,
    warned_80    boolean     NOT NULL DEFAULT false,
    warned_100   boolean     NOT NULL DEFAULT false,
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, metric, period_start),
    CONSTRAINT ck_usage_counters_metric CHECK (metric IN ('QUOTES')),
    CONSTRAINT ck_usage_counters_count CHECK (count >= 0)
);

-- Planes iniciales (hipótesis comerciales: se editan desde el panel antes de salir a producción).
INSERT INTO subscription_plans (id, code, name, description, price, is_free, max_published_calculators,
                                max_monthly_quotes, csv_export, custom_branding, lead_email_notifications,
                                active_for_new, sort_order, created_at, updated_at)
VALUES ('01900000-0000-7000-8000-000000000001', 'GRATIS', 'Gratis',
        'Para probar Climind Cotiza con un cotizador.', 0, true, 1, 30, false, false, true, true, 1, now(), now()),
       ('01900000-0000-7000-8000-000000000002', 'PRO', 'Pro',
        'Para negocios que cotizan todas las semanas.', 9900, false, 3, 300, true, true, true, true, 2, now(), now()),
       ('01900000-0000-7000-8000-000000000003', 'NEGOCIO', 'Negocio',
        'Más cotizadores y capacidad para equipos con varios servicios.', 19900, false, 10, 1500, true, true, true, true, 3,
        now(), now());
