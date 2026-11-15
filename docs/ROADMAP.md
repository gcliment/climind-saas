# Roadmap

## Fase 1: Fundaciones ✔ (completa)

**Resultado:** un usuario puede registrarse, confirmar su email, ingresar a su espacio de trabajo y completar los datos de su negocio.

- [x] Estructura del repo, monolito modular Spring Boot 4.1 / Java 21
- [x] PostgreSQL + Flyway (V1 plataforma, V2 negocio), validación de esquema
- [x] Registro, verificación de email, login/logout, recuperación de contraseña
- [x] Sesiones en PostgreSQL, CSRF, rate limiting, bloqueo progresivo, auditoría
- [x] Modelo multi-tenant con aislamiento probado
- [x] Perfil del negocio y catálogo de rubros
- [x] Emails transaccionales (SMTP + bandeja de desarrollo)
- [x] SUPER_ADMIN (bootstrap + resumen)
- [x] Frontend: landing de Cotiza, portal SaaS, pantallas de acceso, panel (escritorio y móvil), páginas legales en borrador, 404
- [x] Dockerfile, docker-compose, `.env.example`, healthcheck
- [x] Documentación inicial y tests (60 backend + 6 E2E)

## Fase 2: Negocios y cotizadores ✔ (completa)

**Resultado:** un negocio puede crear un cotizador (desde una plantilla o de cero), configurar preguntas y precios, probarlo con la vista previa calculada por el servidor y publicar versiones inmutables.

- [x] Motor de precios puro (`pricing`) según [BUSINESS_RULES.md](BUSINESS_RULES.md), con 39 tests propios
- [x] V3: `calculators` y `calculator_versions` (inmutables, protegidas por un trigger)
- [x] Plantillas: limpieza, catering y eventos, instalaciones. Precios de ejemplo marcados como tales y validados al arrancar. Son archivos versionados, no una tabla (ver decisión en ARCHITECTURE)
- [x] Constructor con pestañas Información, Preguntas, Precios, Apariencia y Publicación. Vista previa lateral en escritorio y alternada en móvil
- [x] Borrador → publicar versión → pausar / reactivar / archivar / restaurar / duplicar, con bloqueo optimista entre pestañas
- [x] Onboarding de 5 pasos (salteable), con enlace y botón de copiar al final
- [x] Logo del negocio (`storage` en volumen local; validación por magic bytes; SVG rechazado)
- [x] Tests de IDOR de cotizadores

**Movido a la Fase 3:** QR y "Compartir por WhatsApp" del final del onboarding. Solo tienen sentido cuando el enlace público funciona, y la página pública es justamente la Fase 3. Hasta entonces la interfaz lo dice explícitamente y ofrece la vista previa.

## Fase 3: Experiencia pública y consultas ✔ (completa: primera versión demostrable a clientes)

- [x] `/c/{negocio}/{cotizador}` mobile-first con DTO público recortado (sin reglas de precio) y metadatos Open Graph para la vista previa al compartir
- [x] Cálculo en el servidor con la versión publicada; cotización guardada con snapshot inmutable (también protegido por trigger)
- [x] Deduplicación: mismo visitante + misma versión + mismas respuestas en 30 minutos = una sola cotización
- [x] Consulta con consentimiento, campo trampa anti-bots y límites por IP; datos antes o después del precio (configurable)
- [x] Enlace oficial wa.me con el mensaje del negocio; registro del clic (intención, no consulta)
- [x] Página de la cotización emitida (`/cotizacion/{código}#t=token`) y PDF
- [x] Consultas: filtros por estado, búsqueda, cotizador, fechas, orden, paginación, detalle con línea de tiempo, estados, notas, WhatsApp al cliente, PDF y CSV
- [x] Anonimización a pedido y política de retención nocturna configurable por negocio
- [x] Estadísticas: indicadores, actividad diaria o mensual, cotizadores más usados, distribución de importes (máximo 12 meses)
- [x] Email "nueva consulta" según preferencias (Mi negocio → Notificaciones)
- [x] QR (SVG y PNG) y "Compartir por WhatsApp" en la pestaña Publicación y al final del onboarding
- [x] Panel de inicio con actividad del mes, consultas sin responder y accesos rápidos

**Pendiente para la Fase 4:** las cotizaciones todavía no consumen cupo de un plan (no hay planes). La deduplicación ya está lista para que el cupo no se infle al recalcular. El estado del plan en el panel de inicio llega con los planes.

## Fase 4: Monetización ✔ (completa con el simulador; falta probar con credenciales reales de Mercado Pago)

- [x] V5: planes (con sus funciones como columnas tipadas), suscripciones con copia de lo contratado, pagos, eventos del proveedor y contadores de uso
- [x] Cupos en el backend: cotizaciones por período (incremento atómico condicionado, probado con pedidos simultáneos) y cotizadores publicados; avisos por email al 80 % y al 100 %
- [x] Funciones por plan: exportación CSV, color propio y sin la marca de Climind (página pública y PDF), avisos de consultas
- [x] Mercado Pago (suscripción sin plan asociado, pago pendiente): checkout, webhook con firma `x-signature`, idempotencia por notificación, sincronización consultando al proveedor, conciliación cada hora
- [x] Activación solo con un pago aprobado; rechazo → `PAST_DUE` con 7 días de gracia → `EXPIRED` (plan Gratis, sin borrar nada)
- [x] Cambio de plan (subir o bajar), cancelación con beneficios hasta fin del período, checkout abandonado
- [x] Simulador de pagos solo en dev y test (la app no arranca en prod con `PAYMENT_PROVIDER=fake`)
- [x] Panel SUPER_ADMIN: métricas (MRR estimado vs. cobrado), negocios (buscar, plan, uso, suspender o reactivar), suscripciones, pagos, planes (crear y editar), estado de la integración y diferencias de conciliación
- [x] Página "Tu plan" del negocio y consumo en el inicio

**Pendiente:** probar el circuito completo con credenciales de prueba de Mercado Pago (cuenta y usuarios de prueba) y registrar la URL del webhook. Sin prorrateo al cambiar de plan: el plan nuevo empieza con su primer cobro.

## Fase 5: Comercialización ✔ (completa en código; falta la configuración externa para salir a vender)

- [x] Landing de Cotiza con demo en vivo (mismo motor, sin guardar), casos de uso, beneficios, precios desde la base, preguntas frecuentes y llamado a registrarse
- [x] Página de precios con tabla comparativa y preguntas de pagos; portal Climind SaaS con producto, planes y preguntas
- [x] Demos públicas con un negocio ficticio (`/c/demo/...`): sin datos personales, fuera de métricas, cotizaciones de prueba borradas a las 48 h
- [x] Centro de ayuda: 7 artículos con buscador; accesos desde el panel
- [x] Emails: cambio de contraseña desde el panel, baja programada y baja ejecutada (transaccionales); recordatorio de activación y resumen mensual (de producto, con baja en un clic)
- [x] Tu cuenta: nombre, contraseña, preferencias de email, **exportación completa** (ZIP), **baja de cuenta** con 30 días para arrepentirse y ejecución que conserva solo los pagos
- [x] SEO básico: `robots.txt`, `sitemap.xml` y metadatos para compartir
- [x] Servicio de backups (diario, fuera del VPS con rclone, prueba de restauración automática)
- [x] Docker verificado: build, arranque, reinicio sin pérdida de datos, backup y **restauración completa desde cero**
- [x] Pruebas finales: 198 tests backend; 28 recorridos E2E en Chromium (celular y escritorio), también en Safari de iPhone (WebKit) y Firefox
- [x] Despliegue productivo en EasyPanel (15/11/2026): `cotiza.climind-ia.com` y `saas.climind-ia.com`, emails por Google Workspace con SPF, DKIM y DMARC en PASS. Cloudflare queda opcional
- [x] Mercado Pago: aplicación "Climind Cotiza", webhook registrado, circuito probado con cuentas de prueba y luego con cuentas reales (contratación, activación y cancelación)
- [ ] Textos legales revisados por un profesional (borradores actualizados con baja, exportación y emails)

### Escenarios de aceptación del MVP

| # | Escenario | Dónde se verifica |
|---|---|---|
| 1 | Registro, verificación, negocio y panel | `AuthFlowIT`, E2E alta completa y guía de configuración |
| 2 | Plantilla, precios propios y publicación sin el administrador | `CalculatorIT`, E2E guía de configuración y constructor |
| 3 | El cliente cotiza desde el celular con el cálculo correcto | `PublicQuoteIT`, `TemplateCatalogTest`, E2E cotizador público (celular) |
| 4 | El cliente deja sus datos y el negocio ve la consulta | `PublicQuoteIT`, `LeadsIT`, E2E cotizador público |
| 5 | La cotización conserva su importe aunque cambien los precios | `PublicQuoteIT`, `CalculatorIT` (trigger) |
| 6 | Aislamiento entre negocios aun manipulando requests | `TenantIsolationIT`, IDOR en `CalculatorIT` y `LeadsIT`, exportación en `AccountIT` |
| 7 | Límites comerciales en el backend | `BillingIT` (incluye 20 pedidos simultáneos) |
| 8 | Plan pago activado solo con el pago confirmado | `BillingIT`, E2E contratación (simulador), y en producción con Mercado Pago (15/11/2026) |
| 9 | Rechazo o cancelación sin perder datos | `BillingIT`, E2E contratación |
| 10 | Despliegue y reinicio conservando datos | Docker local (DEPLOYMENT.md) y redeploys en EasyPanel sin pérdida de datos |
| 11 | El administrador ve clientes, planes, suscripciones y estado | `BillingIT`, `DemoIT`, E2E administración |
| 12 | Funciona en celulares y navegadores modernos | E2E en Pixel 7, iPhone (WebKit), escritorio Chrome y Firefox; sin scroll horizontal |

## Riesgos y simplificaciones

| Riesgo | Mitigación |
|---|---|
| Sin credenciales de Mercado Pago | Interfaz `PaymentProvider` + proveedor fake solo en dev/test (bloqueado en prod) |
| Docker no disponible en desarrollo | PostgreSQL embebido real en tests; `docker build` se valida en CI o en EasyPanel |
| Recálculos que consumen cupo | Deduplicación por hash de respuestas en una ventana de 30 min |
| Rate limiting en memoria | Suficiente con una instancia; migrar a Bucket4j sobre PostgreSQL si se escala horizontalmente |
| Textos legales | Borradores marcados como tales; revisión profesional antes de vender |
| Facturación fiscal (ARCA) | Fuera del MVP; se resuelve por fuera o con una integración posterior |

Quedan fuera del MVP (el diseño los admite): rol MEMBER con interfaz, múltiples negocios por usuario, fechas que modifican el precio, trial (lo reemplaza el plan GRATIS), S3.

## Después del MVP

WhatsApp Cloud API, recordatorios, iframe embebible, idiomas y monedas, dominios propios, cupones, reglas avanzadas, importar listas de precios, Google Sheets, webhooks salientes, API pública, CRM, cotizadores generados con IA, A/B testing, marca blanca y nuevos productos de Climind SaaS.
