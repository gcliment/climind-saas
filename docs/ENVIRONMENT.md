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
