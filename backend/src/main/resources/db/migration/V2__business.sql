-- =============================================================================
-- V2 — Perfil del negocio, configuración y archivos (logotipo).
-- Los binarios NO se guardan en PostgreSQL: business_assets solo referencia la clave
-- del archivo en el almacenamiento (volumen local o S3 a futuro).
-- =============================================================================

CREATE TABLE business_assets (
    id           uuid         PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    kind         varchar(20)  NOT NULL,
    storage_key  varchar(200) NOT NULL,
    content_type varchar(50)  NOT NULL,
    size_bytes   integer      NOT NULL,
    created_at   timestamptz  NOT NULL,
    CONSTRAINT uq_business_assets_key UNIQUE (storage_key),
    CONSTRAINT ck_business_assets_kind CHECK (kind IN ('LOGO')),
    CONSTRAINT ck_business_assets_size CHECK (size_bytes > 0 AND size_bytes <= 5242880)
);
CREATE INDEX ix_business_assets_tenant ON business_assets (tenant_id, created_at DESC);

CREATE TABLE business_profiles (
    tenant_id     uuid         PRIMARY KEY REFERENCES tenants (id) ON DELETE RESTRICT,
    business_name varchar(120) NOT NULL,
    industry      varchar(40),
    description   varchar(500),
    phone         varchar(30),
    whatsapp      varchar(15),
    contact_email varchar(254),
    website       varchar(200),
    logo_asset_id uuid         REFERENCES business_assets (id) ON DELETE SET NULL,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    CONSTRAINT ck_business_profiles_whatsapp CHECK (whatsapp IS NULL OR whatsapp ~ '^[0-9]{10,15}$')
);

CREATE TABLE business_settings (
    tenant_id             uuid        PRIMARY KEY REFERENCES tenants (id) ON DELETE RESTRICT,
    notify_new_lead_email boolean     NOT NULL DEFAULT true,
    notify_usage_email    boolean     NOT NULL DEFAULT true,
    lead_retention_months integer     NOT NULL DEFAULT 24,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT ck_business_settings_retention CHECK (lead_retention_months BETWEEN 1 AND 120)
);
