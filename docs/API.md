# API

- Prefijo: `/api/v1`. JSON en UTF-8.
- Documentación interactiva (OpenAPI): `/api/docs` y `/api/docs/openapi.json`, habilitada en dev y tests y **deshabilitada en producción** (`API_DOCS_ENABLED=false`).

## Autenticación

1. `GET /api/v1/auth/csrf` deja la cookie `XSRF-TOKEN`.
2. Todo `POST`/`PUT`/`PATCH`/`DELETE` envía el header `X-XSRF-TOKEN` con el valor de esa cookie.
3. `POST /api/v1/auth/login` crea la cookie de sesión `CLIMIND_SESSION` (HttpOnly). El navegador la reenvía sola.
4. Después de login y logout el token CSRF se rota: hay que pedir uno nuevo (`frontend/shared/js/api.js` lo hace automáticamente).

## Errores

```json
{ "code": "VALIDATION_ERROR", "message": "Revisá los datos ingresados.",
  "fieldErrors": [{ "field": "email", "message": "Ingresá un email válido." }],
  "traceId": "3f2a9c1e-7b4d" }
```

| HTTP | `code` frecuentes |
|---|---|
| 400 | `VALIDATION_ERROR`, `MALFORMED_REQUEST`, `INVALID_LINK`, `LINK_EXPIRED`, `CONFIG_INVALID`, `CONFIG_MALFORMED`, `INVALID_ANSWERS` |
| 401 | `UNAUTHORIZED`, `INVALID_CREDENTIALS` |
| 403 | `FORBIDDEN`, `CSRF_INVALID`, `EMAIL_NOT_VERIFIED`, `ACCOUNT_DISABLED`, `NO_TENANT`, `TENANT_SUSPENDED` |
| 404 | `NOT_FOUND` |
| 409 | `CONFLICT`, `STALE_VERSION`, `INVALID_STATUS`, `CALCULATOR_ARCHIVED` |
| 422 | `NOT_PUBLISHABLE` |
| 413 | `PAYLOAD_TOO_LARGE`, `FILE_TOO_LARGE` |
| 429 | `RATE_LIMITED`, `ACCOUNT_LOCKED` |
| 500 | `INTERNAL_ERROR` (incluye `traceId`, que también se registra en el log) |

## Endpoints implementados (Fases 1 a 4)

### Autenticación — públicos salvo `/me`

| Método | Ruta | Descripción | Respuesta |
|---|---|---|---|
| GET | `/auth/csrf` | Token CSRF | `{headerName, token}` |
| POST | `/auth/register` | `{firstName, lastName, email, password, passwordConfirmation, acceptTerms}`. Crea usuario, negocio y perfil, y envía la verificación. **Responde igual si el email ya existe.** | 202 `{message}` |
| POST | `/auth/verify-email` | `{token}`. Idempotente. | 200 `{message}` |
| POST | `/auth/resend-verification` | `{email}`. Respuesta siempre igual. | 202 |
| POST | `/auth/login` | `{email, password}` | 200 `MeResponse` + cookie de sesión |
| POST | `/auth/logout` | Cierra la sesión | 204 |
| GET | `/auth/me` | Usuario y negocio activo (requiere sesión) | `MeResponse` |
| POST | `/auth/forgot-password` | `{email}`. Respuesta siempre igual. | 202 |
| POST | `/auth/reset-password` | `{token, password, passwordConfirmation}`. Cierra todas las sesiones del usuario. | 200 |

`MeResponse`:
```json
{ "user": { "id": "…", "email": "ana@ejemplo.com", "firstName": "Ana", "lastName": "Pérez", "platformRole": null },
  "tenant": { "id": "…", "name": "Limpiezas Ana", "slug": "limpiezas-ana-k3m9p", "role": "OWNER",
              "status": "ACTIVE", "onboardingCompleted": false } }
```

### Negocio — rol OWNER

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/business/profile` | Perfil del negocio de la sesión |
| PUT | `/business/profile` | `{businessName, industry, description, phone, whatsapp, contactEmail, website}`. No acepta identificadores: el negocio sale de la sesión. Normaliza el WhatsApp a formato wa.me y la web a `https://…`. |

### Catálogo — público

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/catalog/industries` | Rubros `[{code, label}]` |

### Administración — rol SUPER_ADMIN

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/admin/summary` | Totales de negocios (activos, suspendidos, altas de 30 días) y usuarios |

### Negocio: logo y onboarding (rol OWNER)

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/business/logo` | Multipart `file`: PNG, JPG o WebP de hasta 2 MB, detectado por contenido (no por extensión). Reemplaza el anterior. Responde `{logoUrl}`. |
| DELETE | `/business/logo` | Quita el logo (204) |
| POST | `/business/onboarding/complete` | Marca el onboarding como terminado: la dirección pública del negocio queda fija. Idempotente. |
| GET | `/public/assets/{id}` | **Público.** Logo con caché inmutable (el id cambia con cada logo nuevo) |

### Cotizadores (rol OWNER; siempre acotados al negocio de la sesión)

Un id de otro negocio responde **404**, no 403: no se revela que existe.

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/calculators?archived=false` | Listado (sin archivados salvo que se pidan) con versión publicada y aviso de cambios sin publicar |
| POST | `/calculators` | `{name, templateCode?}`. Crea un borrador vacío o desde una plantilla (201) |
| GET | `/calculators/{id}` | Detalle con `config` (borrador), `appearance`, `lockVersion`, `publishIssues` (lo que falta para publicar) y `publicUrl` |
| PUT | `/calculators/{id}` | `{name, description, slug, appearance, config, lockVersion}`. Guarda el borrador (no cambia lo publicado). 400 `CONFIG_INVALID` con `issues[]` ubicados por sección y pregunta o regla. 400 `CONFIG_MALFORMED` si hay propiedades desconocidas. 409 `STALE_VERSION` si otra pestaña guardó antes. |
| POST | `/calculators/{id}/publish` | Versión nueva e inmutable (`{calculator, newVersion, versionNo}`). Sin cambios no crea versión. 422 `NOT_PUBLISHABLE` con `issues[]`. |
| POST | `/calculators/{id}/pause` · `/resume` · `/archive` · `/restore` | Cambios de estado. 409 `INVALID_STATUS` si no corresponde. |
| POST | `/calculators/{id}/duplicate` | Copia como borrador (201) |
| POST | `/calculators/{id}/preview` | `{answers, config?}`. Cálculo con el motor real en modo vista previa: no guarda ni consume cupo. Con `config` calcula la versión sin guardar del constructor. Si la configuración tiene problemas, responde `{result: null, configIssues}`. |
| GET | `/calculators/{id}/versions` | Historial `{versionNo, publishedAt, current}` |

### Plantillas (rol OWNER)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/templates` | `{code, name, industry, industryLabel, description, objectives, questionCount, calculatorName}` |
| GET | `/templates/{code}` | Detalle con la configuración completa |

### Negocio: preferencias y QR (rol OWNER)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/business/settings` | Aviso por email de consultas nuevas y meses de retención de los datos de clientes |
| PUT | `/business/settings` | `{notifyNewLeadEmail, leadRetentionMonths}` (de 1 a 120) |
| GET | `/calculators/{id}/qr.svg` | QR del enlace público (para mostrar) |
| GET | `/calculators/{id}/qr.png` | QR del enlace público (descarga para imprimir) |

### Cotizador público (sin sesión ni CSRF; límites por IP)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/public/c/{negocio}/{cotizador}` | Formulario (preguntas sin precios), contacto del negocio, color, aclaración, vigencia y cuándo se piden los datos. Pausado: `available=false` sin preguntas. Borrador, archivado o negocio suspendido: 404 |
| POST | `/public/c/{negocio}/{cotizador}/quotes` | `{answers, contact?}` → 201 con la cotización (código, **token**, desglose, vigencia, enlace de WhatsApp). Respuestas inválidas: 400 `INVALID_ANSWERS` (no se guarda). Pausado: 409. `contact` es obligatorio si el cotizador pide los datos antes del precio (400 `CONTACT_REQUIRED`). 30 por minuto por IP |
| GET | `/public/quotes/{código}` | Cotización emitida. Header `X-Quote-Token` obligatorio; sin token o con otro: 404 |
| POST | `/public/quotes/{código}/request` | `{name, whatsapp, email?, comments?, consent, website}` + `X-Quote-Token` → 201. Una por cotización (409). `website` es un campo trampa y debe llegar vacío. 10 por hora por IP |
| POST | `/public/quotes/{código}/whatsapp` | Registra que el cliente abrió WhatsApp (204) |
| GET | `/public/quotes/{código}/pdf` | PDF de la cotización (`X-Quote-Token`) |

El token nunca viaja en la URL que llega al servidor: la página `/cotizacion/{código}#t={token}` lo lee del fragmento y lo manda como header.

Las páginas `/c/{negocio}/{cotizador}` las sirve `PublicPageController` con título y metadatos Open Graph del cotizador.

### Consultas (rol OWNER; siempre acotadas al negocio de la sesión)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/leads?status=&q=&calculatorId=&from=&to=&sort=&page=&size=` | Listado paginado. `sort`: `recientes` (por defecto), `antiguas`, `mayor_importe`, `menor_importe`. `q` busca en nombre, código, email, cotizador y teléfono |
| GET | `/leads/status-counts` | Cantidad por estado |
| GET | `/leads/export.csv?…` | CSV con los mismos filtros (hasta 5000 filas; queda auditado) |
| GET | `/leads/{id}` | Detalle: contacto, desglose guardado, línea de tiempo y enlace de WhatsApp al cliente |
| PUT | `/leads/{id}/status` | `{status, note?}` (NUEVA, CONTACTADA, EN_NEGOCIACION, CONVERTIDA, PERDIDA) |
| POST | `/leads/{id}/notes` | `{note}` |
| POST | `/leads/{id}/anonymize` | Borra los datos personales (no se puede deshacer) |
| GET | `/leads/{id}/pdf` | PDF de la cotización |

### Estadísticas (rol OWNER)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/stats/summary?from=&to=` | Indicadores, serie (por día hasta 92 días, por mes después), cotizadores (hasta 20) y distribución de importes. Por defecto los últimos 30 días; máximo 12 meses (400 si se excede) |

### Planes y suscripción (rol OWNER, salvo el catálogo)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/catalog/plans` | Planes disponibles para contratar (público) |
| GET | `/billing` | Plan vigente, consumo del período, suscripción, contratación pendiente, planes y pagos |
| POST | `/billing/checkout` | `{planCode}` → `{checkoutUrl}` del proveedor. No activa nada: el plan se habilita al confirmarse el pago. 409 si ya tenés ese plan; 503 si los pagos no están configurados |
| POST | `/billing/checkout/cancel` | Descarta una contratación sin pagar |
| POST | `/billing/cancel` | Cancela el plan pago (baja en el proveedor; conserva beneficios hasta fin del período) |
| POST | `/webhooks/mercadopago` | Notificaciones de Mercado Pago. Firma `x-signature` obligatoria (401 si no valida). Idempotente: una notificación repetida responde `DUPLICATE` |

Errores por límites del plan: `PLAN_LIMIT_CALCULATORS` (409, al publicar o reactivar), `QUOTA_EXCEEDED` (409, cotizador público sin cupo), `PLAN_FEATURE` (403, función no incluida, p. ej. CSV).

### Administración (rol SUPER_ADMIN)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/admin/summary` | Negocios, uso y `revenue` (suscriptores pagos, MRR estimado, cobrado en 30 días, rechazos, cancelaciones) |
| GET | `/admin/tenants?q=&status=&page=&size=` | Negocios con plan, uso, dueño y estado |
| POST | `/admin/tenants/{id}/suspend` | `{reason}` (obligatorio, auditado) |
| POST | `/admin/tenants/{id}/reactivate` | |
| GET | `/admin/plans` | Planes con la cantidad de suscripciones vigentes |
| POST | `/admin/plans` | Crear plan pago |
| PUT | `/admin/plans/{id}` | Editar (solo afecta contrataciones nuevas y al plan gratuito; bloqueo optimista con `lockVersion`) |
| GET | `/admin/subscriptions?status=` | Suscripciones |
| GET | `/admin/payments?status=` | Pagos (p. ej. `REJECTED` para fallos de cobro) |
| GET | `/admin/billing/health` | Proveedor, credenciales, última notificación, errores, última conciliación y correcciones |
| POST | `/admin/billing/reconcile` | Ejecutar la conciliación ahora |

### Solo desarrollo (perfil `dev`)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/dev/emails?to=` | Últimos emails "enviados" con sus links. Denegado fuera de dev. |
| POST | `/api/dev/payments/{sub}/charge?result=approved\|rejected\|in_process` | Simulador: cobra una cuota y envía la notificación firmada |
| POST | `/api/dev/payments/{sub}/status?value=authorized\|paused\|cancelled` | Simulador: cambia el estado de la suscripción y notifica |

Las colecciones van paginadas (`?page=&size=`, con un tamaño máximo de 100).
