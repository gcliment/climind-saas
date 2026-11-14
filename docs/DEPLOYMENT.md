# Despliegue: Hostinger VPS + EasyPanel

## Estado (14/11/2026)

Verificado localmente con Docker 28.3.2 y el mismo `docker-compose.yml` de este repo:

- [x] `docker build` de la imagen de la app (perfil `prod`, frontend empaquetado en el jar) y de la imagen de backups.
- [x] Arranque en limpio: Flyway aplica V1–V6, se crean las demos públicas y el super admin inicial.
- [x] Recorrido real por la API contra el contenedor: registro, email de verificación entregado por SMTP (Mailpit), cotizador publicado, cotización con consulta y logo subido.
- [x] **Reinicio conservando datos** (Escenario 10): con el contenedor de la app eliminado y recreado y PostgreSQL reiniciado, siguen la consulta, el cotizador y el logo. Flyway no reaplica nada.
- [x] **Backup y prueba de restauración automática:** `backup now` genera el dump, los archivos y las sumas de control, y luego restaura en una base temporal y verifica el esquema V6, las tablas y los triggers.
- [x] **Restauración ante desastre:** se borraron los volúmenes de base y de archivos, se restauró desde el backup y la app volvió a servir la misma consulta y el mismo logo.

**No verificado:** el despliegue en el VPS real con EasyPanel, los DNS, Cloudflare, el SMTP productivo, la copia remota con rclone y Mercado Pago real. Dependen de accesos y credenciales que no están en este repositorio. La guía de abajo describe esos pasos.

## Arquitectura de producción

```
Internet ─► Cloudflare (DNS/proxy, opcional) ─► EasyPanel (Traefik, HTTPS) ─► app:8080 ─► postgres:5432
                                                                              └─ volumen /app/storage
                                                   backup (diario 03:00) ─► pg_dump + storage ─► /backups ─► rclone (fuera del VPS)
```

- **app:** imagen del `Dockerfile` de la raíz (API y frontend), con el perfil `prod`. Un solo contenedor.
- **postgres:** PostgreSQL 17 con volumen persistente.
- **backup:** imagen de `infra/backup/` (pg_dump/pg_restore 17 + rclone). Hace un backup diario y prueba que se pueda restaurar.
- **Dominios:** `cotiza.climind-ia.com` y `saas.climind-ia.com` apuntan al **mismo** servicio app. Con `APP_PORTAL_HOST=saas.climind-ia.com`, ese host muestra el portal en `/`.

## Paso a paso en EasyPanel

### 1. Proyecto y base de datos

1. Crear el proyecto `climind-saas`.
2. Agregar un servicio **Postgres**:
   - Versión 17, base `climind_saas`, usuario `climind`.
   - Una contraseña larga y aleatoria.
   - Anotá el **host interno** que muestra EasyPanel (por ejemplo `climind-saas_postgres`).

### 2. Servicio app

- **Source:** el repositorio Git, rama `main`.
- **Build:** Dockerfile, ruta `Dockerfile`, contexto la raíz del repositorio.
- **Environment:** la lista completa está en [ENVIRONMENT.md](ENVIRONMENT.md). Mínimo para producción:
  ```
  DB_URL=jdbc:postgresql://climind-saas_postgres:5432/climind_saas
  DB_USERNAME=climind
  DB_PASSWORD=…
  APP_PUBLIC_URL=https://cotiza.climind-ia.com
  APP_PORTAL_URL=https://saas.climind-ia.com/
  APP_PORTAL_HOST=saas.climind-ia.com
  APP_SUPPORT_EMAIL=…                      (el que se muestra en la ayuda)
  MAIL_PROVIDER=smtp
  SMTP_HOST=… SMTP_PORT=587 SMTP_USERNAME=… SMTP_PASSWORD=… SMTP_AUTH=true SMTP_STARTTLS=true
  MAIL_FROM=Climind Cotiza <no-reply@climind-ia.com>
  ADMIN_BOOTSTRAP_EMAIL=…  ADMIN_BOOTSTRAP_PASSWORD=…   (borrar la contraseña después del primer arranque)
  PAYMENT_PROVIDER=mercadopago
  MERCADOPAGO_ACCESS_TOKEN=…  MERCADOPAGO_WEBHOOK_SECRET=…
  APP_CLIENT_IP_HEADER=CF-Connecting-IP    (solo si TODO el tráfico pasa por Cloudflare)
  ```
- **Domains:** `cotiza.climind-ia.com` y `saas.climind-ia.com`, puerto 8080, HTTPS activado.
- **Mounts:** volumen `storage` en `/app/storage`. Anotá la ruta del host que muestra EasyPanel, porque la usa el servicio de backups.
- **Health check:** la imagen ya trae un `HEALTHCHECK` contra `/actuator/health`. La app tarda unos 15 segundos en arrancar.

### 3. Servicio de backups

Es otro servicio **App** del mismo repositorio:

- **Build:** Dockerfile, ruta `infra/backup/Dockerfile`, contexto `infra/backup`.
- **Environment:**
  ```
  PGHOST=climind-saas_postgres
  PGDATABASE=climind_saas
  PGUSER=climind
  PGPASSWORD=…
  BACKUP_HOUR=3                 (hora de Argentina)
  BACKUP_RETENTION_DAYS=14
  BACKUP_VERIFY=1               (después de cada backup lo restaura en una base temporal y lo verifica)
  RCLONE_REMOTE=r2:climind-backups/cotiza
  RCLONE_CONFIG_R2_TYPE=s3
  RCLONE_CONFIG_R2_PROVIDER=Cloudflare
  RCLONE_CONFIG_R2_ACCESS_KEY_ID=…
  RCLONE_CONFIG_R2_SECRET_ACCESS_KEY=…
  RCLONE_CONFIG_R2_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
  ```
  Cualquier destino de rclone sirve: S3, Backblaze B2, Google Drive. Las credenciales van **solo** en este servicio, no en la app.
- **Mounts:**
  - volumen `backups` en `/backups`;
  - *bind mount* de la carpeta del host del volumen `storage` de la app en `/data/storage`, **solo lectura**.
- **Sin dominio:** no expone ningún puerto.

Si preferís no usar este servicio, EasyPanel puede hacer backups programados de la base a un destino S3. En ese caso igual hace falta copiar el volumen `storage`, donde están los logos.

### 4. Deploy y verificación

1. Hacé el deploy primero de postgres, después de app y por último de backup. Las migraciones de Flyway corren solas cuando arranca la app.
2. Checklist:
   - `https://cotiza.climind-ia.com/actuator/health` responde `{"status":"UP"}`.
   - `https://cotiza.climind-ia.com/` muestra la landing con la demo en vivo y los precios.
   - `https://saas.climind-ia.com/` muestra el portal.
   - `/robots.txt` y `/sitemap.xml` usan el dominio real.
   - Un registro de prueba con un email real recibe el email de confirmación (revisá también la carpeta de spam).
   - La cookie `CLIMIND_SESSION` tiene `Secure; HttpOnly; SameSite=Lax`.
   - En *Administración → Integración* figura "Mercado Pago" con las credenciales y la clave configuradas.
   - En el servicio backup, *Console* → `entrypoint.sh now` termina con `OK: el backup … se restaura completo`.
3. Borrá `ADMIN_BOOTSTRAP_PASSWORD` de las variables y volvé a desplegar.

## Mercado Pago

1. En [Tus integraciones](https://www.mercadopago.com.ar/developers/panel/app), creá la aplicación con el producto Suscripciones.
2. En **Webhooks → Configurar notificaciones**:
   - URL: `https://cotiza.climind-ia.com/api/v1/webhooks/mercadopago`.
   - Eventos de *Planes y suscripciones* (`subscription_preapproval` y `subscription_authorized_payment`).
   - Copiá la clave secreta a `MERCADOPAGO_WEBHOOK_SECRET`.
3. Copiá el access token a `MERCADOPAGO_ACCESS_TOKEN`. Primero el de prueba, con usuarios de prueba; después el productivo.
4. Probá el circuito completo con un usuario de prueba:
   - contratación y pago aprobado;
   - pago rechazado (tarjeta de prueba con rechazo);
   - cancelación.

   En *Administración → Integración*, verificá que lleguen las notificaciones y que la conciliación no muestre diferencias.
5. Revisá los precios en *Administración → Planes* antes de abrir las ventas: los iniciales son una propuesta.

## Cloudflare

- **DNS:** registros `A` o `CNAME` de `cotiza` y `saas` hacia la IP del VPS.
- **Con proxy (nube naranja):**
  - SSL/TLS en **Full (strict)**. EasyPanel emite el certificado de origen con Let's Encrypt; si la emisión falla con el proxy activo, emitilo primero con el proxy apagado o usá un certificado de origen de Cloudflare.
  - `APP_CLIENT_IP_HEADER=CF-Connecting-IP`, solo si **todo** el tráfico pasa por Cloudflare. Así el límite por IP usa la IP real del visitante. Lo ideal es además restringir el firewall del VPS a las IPs de Cloudflare.
  - Caché: no hace falta ninguna regla. La app responde con `Cache-Control: no-cache` o `no-store` en páginas y API, y Cloudflare no cachea HTML por defecto.
  - No actives "Rocket Loader" ni "Email Obfuscation". Modifican el HTML con scripts en línea que la CSP de la app bloquea.
- **Sin proxy:** dejá `APP_CLIENT_IP_HEADER` vacío.

## Proxy y HTTPS

- El perfil `prod` usa `server.forward-headers-strategy=native`: respeta el `X-Forwarded-Proto` de Traefik (red interna de Docker), así la app sabe que la conexión original fue HTTPS.
- HSTS se envía en las respuestas HTTPS. La CSP es estricta: sin scripts ni estilos en línea.

## Migraciones

- Flyway corre al arrancar la app. Si una migración falla, la app **no arranca** y el health check queda en rojo. Revisá los logs y no fuerces el arranque.
- Hibernate solo **valida** el esquema (`ddl-auto=validate`): nunca lo modifica.
- Antes de un deploy con migraciones nuevas, hacé un backup manual: servicio backup → *Console* → `entrypoint.sh now`.
- **Volver atrás:** no hay migraciones "down". Si una versión nueva falla después de migrar, redesplegá la versión anterior. Si la migración no es compatible hacia atrás, restaurá el backup previo (ver abajo).

## Logs

- Salida estándar del contenedor (EasyPanel → *Logs*). Cada request lleva el header `X-Request-Id`; los errores 500 registran ese mismo `traceId`, que el usuario ve como código de soporte.
- No se registran contraseñas, tokens ni cuerpos de email; los emails aparecen enmascarados.
- Jobs nocturnos (hora de Argentina):

  | Hora | Job |
  |---|---|
  | 04:10 | Bajas de cuenta vencidas |
  | 04:30 | Retención de consultas |
  | 04:50 | Limpieza de cotizaciones de las demos |
  | 09:00 | Resumen mensual (solo los días 1 a 3) |
  | 10:00 | Recordatorio de activación |

  La conciliación de pagos corre cada hora.

## Backups

**Qué incluye:**
- la base completa (`pg_dump` en formato custom, comprimido);
- los archivos subidos (`storage`);
- un archivo `.sha256` con las sumas de control.

**Cuándo:** todos los días a `BACKUP_HOUR`. Después de cada backup, `restore-test.sh` lo restaura en una base temporal (`climind_restore_test`) y verifica:
- que la versión del esquema esté completa y sin migraciones fallidas;
- que existan las tablas principales y haya un plan gratuito;
- que estén los triggers de inmutabilidad.

Si algo falla, el log del servicio lo muestra con `FALLÓ`.

**Retención:** `BACKUP_RETENTION_DAYS` (14 por defecto), en el volumen local y en el remoto.

**Fuera del VPS:** con `RCLONE_REMOTE`. Sin un destino remoto, un problema del VPS se lleva también los backups. Configuralo antes de vender.

### Comandos

Con el stack de `infra/docker-compose.yml`. En EasyPanel, los mismos scripts se ejecutan desde la *Console* del servicio backup.

```bash
# Backup inmediato (+ prueba de restauración)
docker compose -f infra/docker-compose.yml --env-file infra/.env run --rm backup now

# Solo la prueba de restauración del último backup (no toca producción)
docker compose -f infra/docker-compose.yml --env-file infra/.env run --rm backup restore-test.sh
```

### Restaurar (procedimiento probado)

1. **Detené la app.** En EasyPanel, *Stop* en el servicio app.
2. Ejecutá la restauración. Sin `RESTORE_CONFIRM=si`, el script solo verifica las sumas de control y se detiene. El servicio de backups monta los archivos en **solo lectura**, así que para restaurarlos hay que montar el volumen `storage` en otra ruta con escritura:
   ```bash
   docker compose -f infra/docker-compose.yml --env-file infra/.env run --rm \
     -e RESTORE_CONFIRM=si -e STORAGE_DIR=/restore/storage -v climind-saas_storage:/restore/storage \
     backup restore.sh                    # último backup; o: restore.sh 20261114T191553Z
   ```
   En EasyPanel: agregá temporalmente al servicio backup un mount escribible del volumen `storage` en `/restore/storage` y ejecutá `RESTORE_CONFIRM=si STORAGE_DIR=/restore/storage restore.sh` desde su *Console*.

   Si solo hay que recuperar la base, usá `restore.sh <marca> --db-only`.
3. Iniciá la app. Flyway confirma que el esquema está al día ("Schema is up to date").
4. Verificá un cotizador público y el panel de administración.

Desde Windows con Git Bash, anteponé `MSYS_NO_PATHCONV=1` a los comandos que tienen rutas: si no, Git Bash convierte `/restore/storage` en una ruta de Windows.

## Actualizaciones

- Cada deploy reconstruye la imagen. Los datos viven en el volumen de PostgreSQL y en `storage`, y sobreviven a los reinicios y las actualizaciones (verificado).
- Las sesiones están en PostgreSQL: un redeploy **no** cierra la sesión de los usuarios.

## Probar todo localmente con Docker

```bash
cp infra/.env.example infra/.env        # completar DB_PASSWORD; COOKIE_SECURE=false para http://localhost
docker compose -f infra/docker-compose.yml --env-file infra/.env up --build
# App: http://localhost:8080/   Emails: http://localhost:8025/
```

Si los puertos 5432, 8025 u 8080 están ocupados en tu máquina, cambialos con un archivo `docker-compose.override.yml` (por ejemplo `ports: !override ["127.0.0.1:18025:8025"]`).
