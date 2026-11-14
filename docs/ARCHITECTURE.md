# Arquitectura

## Decisión general: monolito modular en un solo contenedor

Un único proceso Spring Boot sirve **la API** (`/api/v1/**`) y **el frontend estático** (HTML/CSS/JS). PostgreSQL es el único servicio adicional.

¿Por qué así?
- **Costo y mantenimiento mínimos** (Prioridad 4): un solo contenedor en EasyPanel, sin nginx intermedio ni servicios extra.
- **Mismo origen** para páginas y API: la sesión va en una cookie HttpOnly con `SameSite=Lax`, el CSRF es simple y no hace falta CORS.
- **Sin paso de build del frontend**: ES Modules nativos y Bootstrap vendorizado. Cualquier persona del equipo puede editar una página.

Microservicios, colas o caches distribuidas no aportan valor en esta etapa. El diseño modular permite extraer piezas más adelante si hiciera falta.

## Módulos del backend (`com.climind.saas`)

| Paquete | Responsabilidad | Estado |
|---|---|---|
| `shared` | Configuración, errores (`ApiError`), seguridad HTTP, rate limiting, auditoría, utilidades (UUIDv7, tokens, slugs) | Fase 1 ✔ |
| `auth` | Usuarios, registro, verificación de email, login/logout, recuperación de contraseña, bootstrap del super admin | Fase 1 ✔ |
| `tenants` | Negocios (tenants), membresías, `CurrentTenant` | Fase 1 ✔ |
| `businesses` | Perfil y configuración del negocio, catálogo de rubros | Fase 1 ✔ |
| `notifications` | Emails transaccionales (SMTP / registro en memoria en dev), plantillas Thymeleaf | Fase 1 ✔ |
| `administration` | Panel SUPER_ADMIN | Fase 1 (resumen), Fase 4 (completo) |
| `pricing` | Motor de cálculo puro (sin Spring/JPA): contrato de configuración, validador y motor | Fase 2 ✔ |
| `calculators` | Cotizadores, versiones inmutables, plantillas, vista previa | Fase 2 ✔ |
| `quotes` | Cotizador público, cotizaciones guardadas, consultas, PDF (OpenHTMLtoPDF), CSV y retención | Fase 3 ✔ |
| `analytics` | Estadísticas (agregados SQL acotados) | Fase 3 ✔ |
| `subscriptions` | Planes, plan vigente, cupos, suscripciones, Mercado Pago (y simulador de dev/test), webhooks, conciliación | Fase 4 ✔ |
| `storage` | Abstracción de archivos (volumen local; preparada para S3) y detección de imágenes por magic bytes | Fase 2 ✔ |

Reglas verificadas por `ArchitectureTest` (ArchUnit):
- Sin dependencias circulares entre módulos.
- `shared` no depende de módulos de negocio.
- Los controllers no usan repositorios: pasan por servicios.
- Los controllers no exponen entidades JPA: devuelven DTOs.
- `pricing` no depende de Spring, JPA, Jackson databind ni de otros módulos (solo de las anotaciones de Jackson).

Dentro de cada módulo: `*Controller` → `*Service` → `*Repository` / entidades, con DTOs como records (`*Dtos`) y mappers MapStruct donde simplifican.

## Multi-tenant

- Modelo de **discriminador**: cada tabla privada tiene `tenant_id`.
- El negocio activo se guarda en la sesión (`AppPrincipal.tenantId`) al iniciar sesión, a partir de la membresía del usuario. **Nunca** se toma de un parámetro, header, cookie o body.
- Los servicios privados obtienen el tenant con `CurrentTenant.requireId()`, que además verifica que el negocio no esté suspendido.
- Los repositorios de recursos privados se consultan siempre con el `tenantId` (`findByIdAndTenantId`, o PK = tenant).
- `TenantIsolationIT` demuestra que un negocio no puede leer ni escribir datos de otro manipulando body, query, headers o cookies. Se amplía en cada fase con cada recurso nuevo.
- Usuarios con más de un negocio: el modelo lo soporta (`tenant_memberships`). La interfaz para cambiar de negocio no está en el MVP.

## Seguridad

| Tema | Implementación |
|---|---|
| Sesión | Spring Session JDBC (tabla `spring_session`); cookie `CLIMIND_SESSION` con HttpOnly, SameSite=Lax y Secure en producción. Se configura en `SessionCookieConfig` porque Boot 4 no aplica `server.servlet.session.cookie.*` a Spring Session. |
| Fijación de sesión | Cambio de id de sesión al iniciar sesión. |
| CSRF | `csrf.spa()`: cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN`. Se rota al iniciar y cerrar sesión. Exentos: `/api/v1/public/**` (cotizador público, sin sesión) y `/api/v1/webhooks/**` (firmados). |
| Contraseñas | BCrypt (costo 12), mínimo 10 caracteres y máximo 72 bytes (límite de BCrypt); se rechazan las comunes y las que contienen el email. |
| Fuerza bruta | Rate limit por IP en `/api/v1/auth/*` (20/min) y por email en login (10/15 min). Bloqueo progresivo de la cuenta: 5 fallos = 15 min, y se duplica en cada bloqueo hasta 24 h. |
| Enumeración de cuentas | Registro, recuperación y reenvío responden siempre igual; el login devuelve el mismo mensaje y tiene un tiempo de respuesta equivalente exista o no el email. |
| Tokens por email | 32 bytes aleatorios; se persiste solo el SHA-256; un solo uso; vencen a las 24 h (verificación) y 1 h (recuperación); se quitan de la URL al abrirlos. |
| Headers | CSP estricta (`script-src 'self'`, sin inline), `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, HSTS, `Permissions-Policy`. |
| Tamaño de requests | JSON de hasta 64 KB (también con chunked) y multipart de hasta 2 MB. |
| Errores | Formato `ApiError` sin stacktraces ni detalles internos; `traceId` para soporte. |
| Logs | Emails enmascarados; nunca se registran contraseñas, tokens ni cuerpos de email. |
| Auditoría | Tabla `audit_logs` (registro, verificación, logins, bloqueos, cambios de perfil, acciones administrativas). |

## Frontend

```
frontend/cotiza/   páginas de la app (se sirven en "/")
frontend/saas/     portal Climind SaaS (en "/saas/" o en "/" del host del portal)
frontend/shared/   css/, js/, img/, fonts/ (Bricolage Grotesque + Instrument Sans, OFL, autoalojadas)
frontend/vendor/   bootstrap-5.3.8 (fijo)
```

- `shared/js/api.js`: cliente `fetch` con CSRF automático y errores tipados.
- `shared/js/ui.js`: errores de formulario accesibles (`aria-invalid`, foco en el primer error) y estados de carga.
- `shared/js/panel.js`: control de sesión del panel, navegación (barra lateral en escritorio, pestañas inferiores en móvil) y cierre de sesión.
- `/site-config.js`: URLs que dependen del entorno, como script externo para respetar la CSP.
- Identidad: la "C" oficial de Climind; en Cotiza el cuadrado de la marca se convierte en una etiqueta de precio. La pieza visual distintiva del producto es el **ticket de presupuesto** (borde dentado y total grande).

## Decisiones registradas

| Decisión | Motivo |
|---|---|
| Sesión de servidor en PostgreSQL en lugar de JWT | Invalidación inmediata (cambio de contraseña, suspensión), sin tokens en el navegador, sobrevive a reinicios. |
| Frontend servido por Spring Boot | Un contenedor, mismo origen, sin CORS. |
| PostgreSQL embebido (zonky) en tests en lugar de Testcontainers | No depende de un daemon de Docker; es PostgreSQL 17 real. Ver TESTING.md. |
| UUID v7 generados por la app | Ordenados por tiempo (índices compactos) y no adivinables. |
| Emails sin cola persistente | Los cuerpos contienen tokens: no se guardan. Si un envío falla queda en `email_delivery_logs` y el usuario puede pedir un reenvío. Los emails sin token (avisos de consultas, Fase 3) podrán reintentarse. |
| Slug provisorio que acompaña al nombre hasta terminar el onboarding | Evita enlaces feos sin romper enlaces ya compartidos. |
| `char(n)` reemplazado por `varchar(n)` | Validación de esquema de Hibernate sin conversiones especiales. |
| Vista previa calculada por el servidor (no en JS) | Un solo motor: lo que ve el dueño en el constructor es exactamente lo que va a cotizar. El constructor manda la configuración sin guardar a `POST /preview` con debounce. El navegador solo decide qué preguntas mostrar (`quote-form.js`) y el servidor lo vuelve a validar. |
| Configuración como JSONB tipado | Una sola fila por versión, fácil de copiar como snapshot inmutable. La consistencia la garantiza `ConfigValidator` (claves, referencias, rangos y límites), no el esquema SQL. |
| Plantillas como archivos y no como tabla | Se versionan y revisan con el código, se validan al arrancar y evitan una tabla más mientras no haya edición desde el panel. |
| Versiones inmutables con trigger | La inmutabilidad no depende solo del código: la base rechaza UPDATE y DELETE sobre versiones publicadas. |
| Bloqueo optimista en cotizadores | Dos pestañas editando el mismo cotizador no se pisan: la segunda recibe `STALE_VERSION`. |
| Botón "Ver precio estimado" en la página pública (no cálculo en vivo) | Cada cotización guardada es una decisión del cliente con respuestas completas. Calcular en vivo guardaría una cotización por cambio e inflaría estadísticas y cupos. Si el cliente cambia una respuesta, el ticket se marca desactualizado hasta recalcular. |
| Página pública con metadatos del servidor | El HTML es estático, pero `PublicPageController` completa título y Open Graph para que el enlace compartido por WhatsApp muestre negocio y servicio. |
| Token de la cotización en el fragmento de la URL y en un header | No queda en registros del servidor ni de proxies, ni se filtra por `Referer`. |
| QR y PDF generados en el servidor | ZXing y OpenHTMLtoPDF (con Noto Sans incrustada, licencia OFL): sin librerías en el navegador ni servicios externos. El PDF solo acepta recursos `data:` y nunca descarga nada. |
| Funciones del plan como columnas | Pocas y tipadas: validación en la base y consultas simples (ver DATABASE.md). |
| Copia de lo contratado en cada suscripción | Editar un plan nunca cambia contratos vigentes; Mercado Pago sigue cobrando el monto con el que se creó la suscripción. |
| Proveedor de pagos detrás de una interfaz | `MercadoPagoProvider` en producción y `FakePaymentProvider` solo en dev y test (bean con `@Profile`, y la app no arranca en prod con `PAYMENT_PROVIDER=fake`). El simulador firma sus notificaciones con el mismo esquema, así que el camino del webhook se prueba completo. |
| Estado de pagos siempre consultado al proveedor | Las notificaciones solo avisan "algo cambió": el estado se lee de la API. Así un webhook falso, repetido o fuera de orden no puede habilitar beneficios. |
| Cupo con `INSERT … ON CONFLICT … WHERE count < límite` | Atómico sin locks explícitos y dentro de la transacción de la cotización (si falla, el consumo se revierte). |
| Gráficos SVG propios | Dos tipos de gráfico no justifican una librería: cumplen la CSP estricta, tienen tooltip con teclado y vista de tabla. |

## Constructor de cotizadores (frontend)

```
shared/js/calculator-model.js   tipos en lenguaje del usuario, altas, borrados consistentes, importes en formato argentino
shared/js/quote-form.js         formulario del cliente + ticket (constructor ahora, página pública en Fase 3)
shared/js/editor.js             pestañas, edición de preguntas y precios, guardar/publicar, vista previa en vivo
shared/js/onboarding.js         asistente de 5 pasos
shared/css/builder.css          estilos del constructor, formulario del cliente y onboarding
```

- Las claves de preguntas, opciones y reglas se generan una vez y **no cambian** al editar el texto (las reglas las referencian).
- Borrar o cambiar el tipo de una pregunta quita también las reglas y condiciones que dependen de ella, con confirmación previa.
- Importes en formato argentino: `50.000`, `1.234,56`. El punto separa miles y la coma, decimales.
