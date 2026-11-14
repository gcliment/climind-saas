# Base de datos

PostgreSQL 17. El esquema se gestiona **exclusivamente con Flyway** (`backend/src/main/resources/db/migration`). Hibernate corre con `ddl-auto=validate` en todos los perfiles: si una entidad no coincide con el esquema, la app no arranca.

## Convenciones

- PK `uuid` (v7, generadas por la app). Excepción: `audit_logs` usa `bigint identity`.
- Fechas `timestamptz`, siempre en UTC (`hibernate.jdbc.time_zone=UTC`). La presentación usa America/Argentina/Buenos_Aires.
- Montos `numeric(14,2)` (desde la Fase 2). Nunca `float` ni `double`.
- Enums como `varchar` + `CHECK`.
- FKs `ON DELETE RESTRICT` para datos de negocio. Archivar = cambiar estado, nunca borrar.
- Emails normalizados en minúscula (`CHECK email = lower(btrim(email))`).

## Migraciones aplicadas

### V1 — plataforma

| Tabla | Para qué | Claves e índices |
|---|---|---|
| `users` | Cuentas (dueños de negocio y super admin) | `uq_users_email`; checks de rol, estado y normalización |
| `tenants` | Negocios (espacios de trabajo) | `uq_tenants_slug`; check de formato del slug; estado ACTIVE/SUSPENDED |
| `tenant_memberships` | Usuario ↔ negocio con rol OWNER/MEMBER | `uq (tenant_id, user_id)`, índice por `user_id` |
| `email_verification_tokens` | Tokens de verificación (solo hash) | `uq token_hash`, índice por usuario |
| `password_reset_tokens` | Tokens de recuperación (solo hash) | ídem |
| `audit_logs` | Auditoría (sin FK a propósito) | índices por tenant, actor y acción, por fecha desc |
| `email_delivery_logs` | Resultado de cada envío (sin cuerpo) | índices por fecha y tenant |
| `spring_session`, `spring_session_attributes` | Sesiones HTTP (Spring Session JDBC) | índice por `principal_name` para invalidar por usuario |

### V2 — negocio

| Tabla | Para qué |
|---|---|
| `business_profiles` | Nombre comercial, rubro, contacto, WhatsApp (formato wa.me), logo. PK = `tenant_id`. |
| `business_settings` | Notificaciones y retención de consultas (1 a 120 meses). PK = `tenant_id`. |
| `business_assets` | Referencias a archivos (logo) en el almacenamiento externo. **No guarda binarios.** |

### V3: cotizadores

| Tabla | Para qué | Claves e índices |
|---|---|---|
| `calculators` | Cotizador del negocio: borrador (`draft_config` jsonb), apariencia (jsonb), estado (BORRADOR/PUBLICADO/PAUSADO/ARCHIVADO), versión publicada vigente, `lock_version` (bloqueo optimista) | `uq (tenant_id, slug)`, check de formato del slug, check "publicado o pausado ⇒ tiene versión", índice `(tenant_id, status, updated_at desc)` |
| `calculator_versions` | Versión publicada: copia de nombre, descripción, configuración y apariencia + `config_hash` | `uq (calculator_id, version_no)`. **Trigger `trg_calculator_versions_immutable`: rechaza UPDATE y DELETE.** |

La configuración se guarda como JSONB versionado (`schemaVersion: 1`) y se valida en la aplicación (`pricing.ConfigValidator`) antes de persistirse: estructura al guardar el borrador y completitud al publicar. El contrato está en [BUSINESS_RULES.md](BUSINESS_RULES.md).

Las **plantillas no son una tabla**: son archivos JSON versionados con el código (`backend/src/main/resources/calculator-templates/`) que se validan al arrancar. Un cotizador creado desde una plantilla guarda una copia propia y `template_code` como referencia de origen. Si en el futuro hiciera falta editarlas desde el panel de administración, se agrega la tabla en una migración.

### V4: cotizaciones y consultas

| Tabla | Contenido |
|---|---|
| `quote_simulations` | Cotización calculada por un cliente: código `COT-XXXXXX` (único), token de acceso, versión usada, respuestas, desglose completo (`result`), total, moneda, escala de redondeo, aclaración, vigencia, hash de respuestas y de visitante (deduplicación) y clic en WhatsApp. Un trigger impide cambiar importe, versión, código, vigencia, líneas o total; solo se permite registrar el clic y borrar los textos libres al anonimizar. |
| `quote_requests` | Consulta (una por cotización): datos de contacto, consentimiento, estado (`NUEVA`, `CONTACTADA`, `EN_NEGOCIACION`, `CONVERTIDA`, `PERDIDA`) y copias del código, cotizador, total y resumen para listar y exportar. Los datos personales pueden quedar en NULL solo si `anonymized_at` está cargado. |
| `quote_status_history` | Línea de tiempo: alta, cambios de estado y notas (las notas se borran al anonimizar). |

Índices por `(tenant_id, created_at desc)` en las tres, `(tenant_id, status, created_at)` en consultas y un índice parcial para la deduplicación.

**Token de acceso en claro.** `access_token` se guarda sin hashear a propósito: da acceso de lectura a datos que ya están en la misma base (hashearlo no protege nada ante una filtración de la base) y permite devolver el mismo enlace cuando una cotización se deduplica. Los tokens de cuenta (verificación y recuperación) sí se guardan como hash, porque dan control de la cuenta.

### V5: planes, suscripciones y pagos

| Tabla | Contenido |
|---|---|
| `subscription_plans` | Planes editables desde el panel: precio, período, límites (cotizadores publicados, cotizaciones por mes) y funciones (CSV, color propio y sin marca, avisos de consultas), disponible para contratar, orden. Un solo plan gratuito (índice único parcial). Semilla: GRATIS, PRO y NEGOCIO. |
| `tenant_subscriptions` | Contrato de un plan pago: estado interno, proveedor e id externo (único), link de checkout, **copia** de precio, límites y funciones contratados, período pagado, gracia, cancelación y fin. Índices únicos parciales: una vigente y un checkout pendiente por negocio. |
| `payment_transactions` | Cobros informados por el proveedor, únicos por `(provider, external_id)`: estado, importe, fecha de cobro y de acreditación. |
| `payment_provider_events` | Notificaciones procesadas (única por `(provider, event_key)` = idempotencia), errores y correcciones de la conciliación. Solo guarda ids y tipo, no el cuerpo completo. |
| `usage_counters` | Consumo por negocio, métrica y período (`QUOTES`), con marcas de aviso al 80 % y 100 %. |

**Funciones como columnas y no como tabla `plan_features`.** Son pocas, conocidas y con tipo (booleano o entero): las columnas dan validación (`CHECK`) y consultas simples. Si aparecieran funciones configurables sin cambiar código, se agrega la tabla.

## Próximas migraciones (diseño aprobado)

| Versión | Fase | Tablas |
|---|---|---|

## Operación

- Las migraciones corren automáticamente al iniciar la app (Flyway, con bloqueo propio).
- **Nunca editar una migración ya aplicada**: crear una nueva (`V3__...sql`).
- Backups y restore: ver [DEPLOYMENT.md](DEPLOYMENT.md#backups).
