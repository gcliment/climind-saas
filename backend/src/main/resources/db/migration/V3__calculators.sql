-- =============================================================================
-- V3 — Cotizadores y versiones publicadas.
-- La configuración (preguntas, reglas de precio y textos comerciales) se guarda como JSONB versionado
-- (schemaVersion) y se valida en la aplicación (pricing.ConfigValidator) antes de persistirse.
-- Las plantillas son archivos versionados con el código (classpath: calculator-templates/), no una tabla.
-- =============================================================================

CREATE TABLE calculators (
    id                   uuid         PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    name                 varchar(120) NOT NULL,
    description          varchar(500),
    slug                 varchar(60)  NOT NULL,
    status               varchar(20)  NOT NULL DEFAULT 'BORRADOR',
    currency             varchar(3)   NOT NULL DEFAULT 'ARS',
    template_code        varchar(40),
    draft_config         jsonb        NOT NULL,
    appearance           jsonb        NOT NULL DEFAULT '{}'::jsonb,
    published_version_id uuid,
    published_at         timestamptz,
    archived_at          timestamptz,
    lock_version         integer      NOT NULL DEFAULT 0,
    created_at           timestamptz  NOT NULL,
    updated_at           timestamptz  NOT NULL,
    CONSTRAINT uq_calculators_tenant_slug UNIQUE (tenant_id, slug),
    CONSTRAINT ck_calculators_slug CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{1,58}[a-z0-9])$'),
    CONSTRAINT ck_calculators_status CHECK (status IN ('BORRADOR', 'PUBLICADO', 'PAUSADO', 'ARCHIVADO')),
    -- Publicado o pausado implica que existe una versión publicada.
    CONSTRAINT ck_calculators_published CHECK (status NOT IN ('PUBLICADO', 'PAUSADO') OR published_version_id IS NOT NULL)
);
CREATE INDEX ix_calculators_tenant ON calculators (tenant_id, status, updated_at DESC);

CREATE TABLE calculator_versions (
    id            uuid         PRIMARY KEY,
    calculator_id uuid         NOT NULL REFERENCES calculators (id) ON DELETE RESTRICT,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    version_no    integer      NOT NULL,
    name          varchar(120) NOT NULL,
    description   varchar(500),
    config        jsonb        NOT NULL,
    appearance    jsonb        NOT NULL,
    config_hash   varchar(64)  NOT NULL,
    published_by  uuid         REFERENCES users (id) ON DELETE RESTRICT,
    published_at  timestamptz  NOT NULL,
    CONSTRAINT uq_calculator_versions_no UNIQUE (calculator_id, version_no),
    CONSTRAINT ck_calculator_versions_no CHECK (version_no >= 1)
);
CREATE INDEX ix_calculator_versions_tenant ON calculator_versions (tenant_id, calculator_id, version_no DESC);

ALTER TABLE calculators
    ADD CONSTRAINT fk_calculators_published_version
        FOREIGN KEY (published_version_id) REFERENCES calculator_versions (id) ON DELETE RESTRICT;

-- Las versiones publicadas son INMUTABLES: las cotizaciones históricas apuntan a ellas.
CREATE FUNCTION calculator_versions_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'calculator_versions es inmutable (% no permitido)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_calculator_versions_immutable
    BEFORE UPDATE OR DELETE ON calculator_versions
    FOR EACH ROW EXECUTE FUNCTION calculator_versions_immutable();
