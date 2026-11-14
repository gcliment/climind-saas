# Despliegue: Hostinger VPS + EasyPanel

> Estado: la imagen, la configuración y las verificaciones están listas desde la Fase 1. El despliegue productivo real, los backups automáticos y la **prueba de restore** son entregables de la Fase 5.

## Arquitectura de producción

```
Internet ─► Cloudflare (DNS/proxy, opcional) ─► EasyPanel (Traefik, HTTPS) ─► app:8080 ─► postgres:5432
                                                                              └─ volumen /app/storage
```

- **app**: imagen construida desde el `Dockerfile` de la raíz (API + frontend), perfil `prod`.
- **postgres**: servicio PostgreSQL 17 de EasyPanel, con volumen persistente.
- Dominios: `cotiza.climind-ia.com` y `saas.climind-ia.com` apuntan al **mismo** servicio. `APP_PORTAL_HOST=saas.climind-ia.com` hace que ese host muestre el portal en `/`.

## Paso a paso en EasyPanel

1. **Proyecto** `climind-saas`.
2. **Servicio PostgreSQL** (`postgres:17`):
   - Base `climind_saas`, usuario `climind` y una contraseña larga.
   - Anotá el host interno (por ejemplo `climind-saas_postgres`).
3. **Servicio App**:
   - *Source*: repositorio Git y rama `main`. *Build*: Dockerfile, ruta `Dockerfile`, contexto la raíz del repo.
   - *Environment*: ver [ENVIRONMENT.md](ENVIRONMENT.md). Mínimo:
     ```
     DB_URL=jdbc:postgresql://climind-saas_postgres:5432/climind_saas
     DB_USERNAME=climind
     DB_PASSWORD=…
     APP_PUBLIC_URL=https://cotiza.climind-ia.com
     APP_PORTAL_URL=https://saas.climind-ia.com/
     APP_PORTAL_HOST=saas.climind-ia.com
     MAIL_PROVIDER=smtp
     SMTP_HOST=… SMTP_PORT=587 SMTP_USERNAME=… SMTP_PASSWORD=… SMTP_AUTH=true SMTP_STARTTLS=true
     MAIL_FROM=Climind Cotiza <no-reply@climind-ia.com>
     ADMIN_BOOTSTRAP_EMAIL=…  ADMIN_BOOTSTRAP_PASSWORD=…   (borrar después del primer arranque)
     MERCADOPAGO_ACCESS_TOKEN=…  MERCADOPAGO_WEBHOOK_SECRET=…
     ```
   - *Domains*: `cotiza.climind-ia.com` y `saas.climind-ia.com`, puerto 8080, HTTPS activado.
   - *Mounts*: volumen `storage` en `/app/storage`.
   - *Health check*: la imagen ya trae `HEALTHCHECK` contra `/actuator/health`.
4. **Deploy**. Las migraciones Flyway corren solas al arrancar.
5. Verificación:
   - `https://cotiza.climind-ia.com/actuator/health` responde `{"status":"UP"}`.
   - Registro de prueba con un email real.
   - Revisar que llegue el email y que la cookie `CLIMIND_SESSION` tenga `Secure; HttpOnly; SameSite=Lax`.

## Mercado Pago

1. En [Tus integraciones](https://www.mercadopago.com.ar/developers/panel/app) crear la aplicación (producto: Suscripciones).
2. **Webhooks → Configurar notificaciones**: URL `https://cotiza.climind-ia.com/api/v1/webhooks/mercadopago`, eventos *Planes y suscripciones* (`subscription_preapproval` y `subscription_authorized_payment`). Copiar la clave secreta a `MERCADOPAGO_WEBHOOK_SECRET`.
3. Copiar el access token a `MERCADOPAGO_ACCESS_TOKEN` (primero el de prueba, con usuarios de prueba; después el productivo).
4. Verificar en *Administración → Integración*: credenciales y clave configuradas, y que llegue la primera notificación. La conciliación corre cada hora y también se puede ejecutar a mano.
5. Revisar los precios de los planes en *Administración → Planes* antes de abrir las ventas (los iniciales son hipótesis).

## Cloudflare

- DNS: registros `A`/`CNAME` de `cotiza` y `saas` hacia la IP del VPS.
- Con proxy naranja activo:
  - SSL **Full (strict)**: EasyPanel emite el certificado de origen con Let's Encrypt. Si falla la emisión con el proxy activo, emitir primero con el proxy apagado o usar un certificado de origen de Cloudflare.
  - `APP_CLIENT_IP_HEADER=CF-Connecting-IP`, solo si **todo** el tráfico pasa por Cloudflare. Idealmente, además, restringir el firewall del VPS a las IPs de Cloudflare.
- Sin proxy: dejar `APP_CLIENT_IP_HEADER` vacío.

## Proxy y HTTPS

- El perfil `prod` usa `server.forward-headers-strategy=native`: respeta `X-Forwarded-Proto` de Traefik, así la app sabe que la conexión original fue HTTPS.
- HSTS se envía en respuestas HTTPS.

## Logs

- Salida estándar del contenedor (EasyPanel → *Logs*). Cada request lleva el header `X-Request-Id`, y los errores 500 registran ese mismo `traceId`, que el usuario ve como código de soporte.
- No se registran contraseñas, tokens ni cuerpos de email. Los emails aparecen enmascarados.

## Actualizaciones

- Cada deploy reconstruye la imagen. Los datos viven en el volumen de PostgreSQL y en `storage`, y sobreviven a reinicios y actualizaciones.
- Las sesiones están en PostgreSQL: un redeploy **no** cierra la sesión de los usuarios.
- Antes de un deploy con migraciones nuevas: hacer un backup manual (ver abajo).

## Backups

Procedimiento previsto (los scripts y la prueba de restore se completan en la Fase 5):

```bash
# Backup lógico diario (formato custom, comprimido)
docker exec <contenedor-postgres> pg_dump -U climind -d climind_saas -Fc > climind_saas_$(date +%F).dump
# Archivos subidos
tar czf storage_$(date +%F).tgz -C <ruta-del-volumen-storage> .
```

- Retención sugerida: 7 diarios + 4 semanales, copiados **fuera del VPS** (por ejemplo, almacenamiento S3 compatible).
- Restore:
  ```bash
  docker exec -i <contenedor-postgres> pg_restore -U climind -d climind_saas --clean --if-exists < climind_saas_AAAA-MM-DD.dump
  ```
  Después, reiniciar la app.

## Probar la imagen localmente

```bash
cp infra/.env.example infra/.env
docker compose -f infra/docker-compose.yml --env-file infra/.env up --build
```
