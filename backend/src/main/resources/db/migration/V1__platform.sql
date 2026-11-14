-- =============================================================================
-- V1 — Plataforma: usuarios, negocios (tenants), membresías, tokens, auditoría,
--      registro de emails y sesiones HTTP (Spring Session JDBC).
-- Convenciones: UUID (v7, generados por la aplicación), timestamptz en UTC,
--               enums como varchar + CHECK, sin borrado físico de datos de negocio.
-- =============================================================================

CREATE TABLE users (
    id                  uuid         PRIMARY KEY,
    email               varchar(254) NOT NULL,
    password_hash       varchar(100) NOT NULL,
    first_name          varchar(80)  NOT NULL,
    last_name           varchar(80)  NOT NULL,
    platform_role       varchar(20),
    status              varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    email_verified_at   timestamptz,
    failed_login_count  integer      NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    last_login_at       timestamptz,
    password_changed_at timestamptz  NOT NULL,
    terms_accepted_at   timestamptz  NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_users_platform_role CHECK (platform_role IS NULL OR platform_role IN ('SUPER_ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_failed_logins CHECK (failed_login_count >= 0)
);

CREATE TABLE tenants (
    id                      uuid         PRIMARY KEY,
    name                    varchar(120) NOT NULL,
    slug                    varchar(60)  NOT NULL,
    status                  varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    country                 varchar(2)   NOT NULL DEFAULT 'AR',
    currency                varchar(3)   NOT NULL DEFAULT 'ARS',
    timezone                varchar(64)  NOT NULL DEFAULT 'America/Argentina/Buenos_Aires',
    onboarding_completed_at timestamptz,
    suspended_at            timestamptz,
    suspended_reason        varchar(300),
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    CONSTRAINT uq_tenants_slug UNIQUE (slug),
    CONSTRAINT ck_tenants_slug CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{1,58}[a-z0-9])$'),
    CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- Un usuario puede pertenecer a más de un negocio (el MVP crea uno solo por usuario).
CREATE TABLE tenant_memberships (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    role       varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_tenant_memberships UNIQUE (tenant_id, user_id),
    CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('OWNER', 'MEMBER'))
);
CREATE INDEX ix_tenant_memberships_user ON tenant_memberships (user_id);

-- Tokens de un solo uso. Se guarda únicamente el hash SHA-256 (hex) del token enviado por email.
CREATE TABLE email_verification_tokens (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_email_verification_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX ix_email_verification_tokens_user ON email_verification_tokens (user_id);

CREATE TABLE password_reset_tokens (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_password_reset_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX ix_password_reset_tokens_user ON password_reset_tokens (user_id);

-- Auditoría de acciones relevantes (auth, cambios de configuración, acciones administrativas).
-- Sin FK a propósito: el registro debe sobrevivir a la anonimización de usuarios/negocios.
CREATE TABLE audit_logs (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   timestamptz NOT NULL,
    actor_user_id uuid,
    tenant_id     uuid,
    action        varchar(60) NOT NULL,
    target_type   varchar(40),
    target_id     varchar(64),
    ip            varchar(45),
    metadata      jsonb       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_audit_logs_tenant ON audit_logs (tenant_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_actor ON audit_logs (actor_user_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_action ON audit_logs (action, occurred_at DESC);

-- Registro de entregas de email (metadatos, nunca el cuerpo: puede contener tokens).
CREATE TABLE email_delivery_logs (
    id         uuid         PRIMARY KEY,
    template   varchar(60)  NOT NULL,
    recipient  varchar(254) NOT NULL,
    user_id    uuid,
    tenant_id  uuid,
    status     varchar(20)  NOT NULL,
    error      varchar(500),
    created_at timestamptz  NOT NULL,
    CONSTRAINT ck_email_delivery_logs_status CHECK (status IN ('SENT', 'FAILED'))
);
CREATE INDEX ix_email_delivery_logs_created ON email_delivery_logs (created_at DESC);
CREATE INDEX ix_email_delivery_logs_tenant ON email_delivery_logs (tenant_id, created_at DESC);

-- Sesiones HTTP persistidas (Spring Session JDBC): sobreviven a reinicios y permiten invalidarlas.
CREATE TABLE spring_session (
    primary_id            char(36)     NOT NULL,
    session_id            char(36)     NOT NULL,
    creation_time         bigint       NOT NULL,
    last_access_time      bigint       NOT NULL,
    max_inactive_interval integer      NOT NULL,
    expiry_time           bigint       NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
