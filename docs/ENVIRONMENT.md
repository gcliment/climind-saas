# Variables de entorno

Ejemplo completo: [`infra/.env.example`](../infra/.env.example). **Nunca** commitear valores reales. En EasyPanel se cargan en la pestaña *Environment* del servicio.

## Perfiles

| Perfil | Uso |
|---|---|
| `dev` (por defecto) | Desarrollo local: frontend desde `../frontend/`, Swagger habilitado, emails en memoria (`/api/dev/emails`), super admin de prueba. |
| `test` | Tests automatizados. |
| `prod` | Producción. Lo fija el Dockerfile (`SPRING_PROFILES_ACTIVE=prod`). Exige SMTP real. |

## Variables

| Variable | Requerida en prod | Por defecto | Descripción |
|---|---|---|---|
| `DB_URL` | sí | `jdbc:postgresql://localhost:5432/climind_saas` | JDBC de PostgreSQL |
| `DB_USERNAME` | sí | `climind` | |
| `DB_PASSWORD` | sí | — | |
| `DB_POOL_SIZE` | no | `10` | Conexiones máximas (Hikari) |
| `PORT` | no | `8080` | Puerto HTTP interno |
| `APP_PUBLIC_URL` | sí | `http://localhost:8080` | URL pública de Cotiza, sin barra final. Se usa en los links de los emails. Ej: `https://cotiza.climind-ia.com` |
| `APP_PORTAL_URL` | sí | `http://localhost:8080/saas/` | URL del portal. Ej: `https://saas.climind-ia.com/` |
| `APP_PORTAL_HOST` | no | vacío | Host que muestra el portal en `/`. Ej: `saas.climind-ia.com` |
| `APP_SUPPORT_EMAIL` | recomendada | vacío | Email de soporte que se muestra en la ayuda, la landing y el portal. Vacío = no se muestra (se ofrecen las preguntas frecuentes) |
| `APP_SUPPORT_WHATSAPP` | recomendada | vacío | WhatsApp comercial con código de país (`5492211234567`) para los botones «Escribinos» de la landing y el portal (cotizador a medida). Vacío = esos botones escriben a `APP_SUPPORT_EMAIL` |
| `APP_META_PIXEL_ID` | opcional | vacío | ID del píxel (dataset) de Meta. Carga el píxel solo en la landing, `/precios` y `/registro` (PageView y CompleteRegistration al crear la cuenta) y agrega `connect.facebook.net` y `www.facebook.com` a la CSP. Nunca se carga en el panel ni en los cotizadores públicos. Vacío = sin píxel y CSP sin dominios externos |
| `OPENAI_API_KEY` | recomendada | vacío | Clave de la API de OpenAI para el asistente de ayuda del panel (botón «¿Dudas?»). Vacío = el asistente responde con las preguntas frecuentes, sin IA. Cada respuesta tiene costo: hay límite por usuario |
| `OPENAI_MODEL` | opcional | `gpt-4.1-mini` | Modelo del asistente (el mismo que Control de Gastos) |
| `OPENAI_BASE_URL` | opcional | `https://api.openai.com/v1` | URL de la API (compatible con Chat Completions) |
| `ASSISTANT_MESSAGES_PER_HOUR` / `ASSISTANT_MESSAGES_PER_DAY` | opcional | 30 / 100 | Preguntas al asistente por usuario |
| `APP_DEMO_ENABLED` | no | `true` (`false` en tests) | Crea al arrancar, si falta, el negocio ficticio de las demos públicas (`/c/demo/...`) |
| `ACCOUNT_DELETION_GRACE` | no | `30d` | Plazo para arrepentirse de una baja de cuenta antes de eliminar los datos |
| `COOKIE_SECURE` | — | `true` en prod | Cookie de sesión solo por HTTPS. Poner `false` únicamente para pruebas locales por http. |
| `SESSION_TIMEOUT` | no | `7d` | Inactividad máxima de una sesión |
| `APP_CLIENT_IP_HEADER` | no | vacío | Header con la IP real (`CF-Connecting-IP`), **solo** si todo el tráfico pasa por Cloudflare. Si no, la IP se falsificaría. |
| `MAIL_PROVIDER` | sí | `log` (dev) / `smtp` (prod) | `log` está **prohibido** en prod: la app no arranca. |
| `MAIL_FROM` | sí | `Climind Cotiza <no-reply@climind-ia.com>` | Remitente (dominio con SPF/DKIM configurados) |
| `SMTP_HOST`, `SMTP_PORT` | sí | `localhost`, `1025` | Servidor SMTP (Brevo, Resend, Amazon SES, etc.) |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | según proveedor | vacío | Credenciales SMTP |
| `SMTP_AUTH`, `SMTP_STARTTLS` | según proveedor | `false` | Normalmente `true` / `true` con el puerto 587 |
| `ADMIN_BOOTSTRAP_EMAIL` | primer arranque | vacío | Crea el SUPER_ADMIN si no existe |
| `ADMIN_BOOTSTRAP_PASSWORD` | primer arranque | vacío | Debe cumplir la política de contraseñas. **Borrarla después del primer arranque.** |
| `RATE_LIMIT_AUTH_PER_MINUTE` | no | `20` | Requests de autenticación por IP y por minuto |
| `RATE_LIMIT_API_PER_MINUTE` | no | `300` | Requests de API por IP y por minuto |
| `RATE_LIMIT_EMAIL_PER_HOUR` | no | `5` | Emails de verificación o recuperación por dirección y por hora |
| `RATE_LIMIT_PUBLIC_QUOTES_PER_MINUTE` | no | `30` | Cotizaciones públicas por IP y por minuto |
| `RATE_LIMIT_PUBLIC_LEADS_PER_HOUR` | no | `10` | Consultas (datos de contacto) por IP y por hora |
| `API_DOCS_ENABLED` | no | `false` | Swagger en prod (no recomendado) |
| `STORAGE_DIR` | no | `./storage` (dev) / `/app/storage` (prod) | Carpeta del volumen persistente de archivos (logos; los PDF y QR se generan al momento y no se guardan). En EasyPanel, montar un volumen en `/app/storage`. |

## Pagos (Mercado Pago)

| Variable | Requerida en prod | Por defecto | Descripción |
|---|---|---|---|
| `PAYMENT_PROVIDER` | no | `mercadopago` (prod) / `fake` (dev y test) | `fake` es el simulador: **en prod la app no arranca** con ese valor |
| `MERCADOPAGO_ACCESS_TOKEN` | para vender | vacío | Access token de la aplicación (Tus integraciones → Credenciales). Solo backend, nunca se expone ni se registra. Sin él, la contratación responde "pagos no habilitados" y el panel de administración lo marca |
| `MERCADOPAGO_WEBHOOK_SECRET` | para vender | vacío | Clave secreta de Webhooks (Tus integraciones → Webhooks) para validar `x-signature`. Sin ella, toda notificación se rechaza (401) |
| `MERCADOPAGO_TEST_PAYER_EMAIL` | no (**vacío en producción**) | vacío | Solo para probar con credenciales de prueba: Mercado Pago exige que el pagador también sea una cuenta de prueba (si no, responde "Both payer and collector must be real or test users"). Si se define, todas las suscripciones usan este email de pagador (el del comprador de prueba). La app lo avisa en el log y en Administración → Integración |

## Servicio de backups (`infra/backup`)

Estas variables van en el servicio **backup**, no en la app.

| Variable | Por defecto | Descripción |
|---|---|---|
| `PGHOST`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | — | Conexión a PostgreSQL (variables estándar de libpq) |
| `BACKUP_HOUR` | `3` | Hora del backup diario (hora de Argentina) |
| `BACKUP_RETENTION_DAYS` | `14` | Días que se conservan los backups, en el volumen local y en el remoto |
| `BACKUP_VERIFY` | `1` | Después de cada backup, lo restaura en una base temporal y lo verifica |
| `STORAGE_DIR` | `/data/storage` | Dónde está montado (solo lectura) el volumen de archivos de la app |
| `RCLONE_REMOTE` | vacío | Destino fuera del VPS, p. ej. `r2:climind-backups/cotiza`. Vacío = solo local (**no recomendado**) |
| `RCLONE_CONFIG_<REMOTO>_*` | — | Configuración de rclone por variables (tipo, proveedor, claves, endpoint). En Docker Compose van en `infra/backup.env`, que está ignorado por git |
| `RESTORE_CONFIRM` | — | `si` para que `restore.sh` reemplace los datos. Sin esta variable solo verifica las sumas de control |
