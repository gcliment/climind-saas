# Tests

## Cómo correrlos

```bash
# Backend: unitarios + integración (PostgreSQL real embebido) + arquitectura
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot"   # si el JDK por defecto no es 21
cd backend && ./mvnw test

# E2E en navegador (necesita la app en perfil dev; si no hay una corriendo en :8080, la levanta sola)
cd tests/e2e && npm ci && npx playwright test
# contra una app ya levantada:
E2E_BASE_URL=http://localhost:8080 npx playwright test
```

Playwright 1.63 usa Chromium 1243. Si no está instalado: `npx playwright install chromium`.

## Base de datos en los tests: PostgreSQL embebido en lugar de Testcontainers

La especificación prefiere Testcontainers, pero el entorno de desarrollo no siempre tiene el daemon de Docker activo. Por eso los tests de integración usan **PostgreSQL 17 real** (binarios oficiales empaquetados por *zonky embedded-postgres*): se ejecutan las mismas migraciones Flyway y la misma validación de esquema de Hibernate que en producción. No es H2 ni ningún otro sustituto.

Se inicia **una vez por JVM** (`support/EmbeddedPostgres`) y cada test vacía las tablas en `@BeforeEach`.

## Qué cubren (estado de la Fase 5: 198 tests backend + 28 E2E en Chromium, todos en verde)

### Unitarios
| Clase | Cubre |
|---|---|
| `PasswordPolicyTest` | Largo mínimo, límite de 72 bytes de BCrypt (incluye multibyte), contraseñas comunes o repetitivas, contraseña que contiene el email |
| `UserLockoutTest` | Bloqueo al 5.º intento, duplicación progresiva, tope de 24 h, reinicio tras login exitoso, normalización del email |
| `UtilsTest` | Tokens (unicidad, formato, SHA-256), UUID v7 ordenados, enmascarado de emails, slugs |
| `BusinessNormalizationTest` | WhatsApp a formato wa.me, validación de la web (rechaza `javascript:`), rubros válidos |
| `EmailServiceTest` | Versión de texto plano con los links; `toString` sin datos sensibles |
| `PortalHostFilterTest` | El host del portal muestra el portal en `/`; los demás hosts y rutas no cambian |
| `PricingEngineTest` (8 grupos) | Cada tipo de regla (fijo, por cantidad, por medida con decimales y coma, tramos, opción única y múltiple, por unidad según opción, adicional condicional, precio unitario distinto según cantidad). Orden de aplicación (recargo sobre subtotal, descuento sobre subtotal + recargo, mínimo después de descuentos, impuesto después del mínimo). Descuentos que nunca dejan el total negativo. Impuestos FINAL, EXCLUDED y ADDED. Redondeo a pesos y con centavos, porcentajes fraccionarios, sin errores de coma flotante. Visibilidad (preguntas ocultas ignoradas aunque llegue un valor). Condiciones sin respuesta siempre falsas. Validación de respuestas (rango, enteros, opciones, texto, fechas pasadas, claves desconocidas). Modo vista previa. Importes que superan el máximo. Determinismo. |
| `ConfigValidatorTest` | Borrador vacío válido pero no publicable. Claves duplicadas o inválidas. Referencias inexistentes o de tipo incompatible. Visibilidad que solo depende de preguntas anteriores (sin ciclos). Operadores incompatibles. Importes, porcentajes, impuesto y redondeo fuera de rango. Rangos y tramos. Límites de tamaño. Requisitos para publicar. Textos comerciales. Moneda y versión. |
| `QuoteSupportTest` | Normalización de WhatsApp argentino e internacional y formato para mostrar, importes en formato argentino, mensaje y enlace wa.me, resumen acotado, celdas CSV contra inyección de fórmulas, QR legible (se decodifica y apunta al enlace) |
| `StatsServiceTest` | Rangos "redondos" para la distribución de importes, sin notación científica |
| `TemplateCatalogTest` | Las 3 plantillas reales cargan, son publicables y cotizan lo esperado (cuentas hechas a mano, incluido el precio mínimo) |

### Integración (`*IT`, contexto completo + PostgreSQL)
| Clase | Cubre |
|---|---|
| `AuthFlowIT` | Registro → email → verificación (idempotente) → login → `/me` → logout. Cookie `CLIMIND_SESSION` con HttpOnly y SameSite=Lax. Sesión persistida en PostgreSQL. Hash BCrypt y token guardado solo como hash. Auditoría. Email existente sin revelarlo. Validaciones por campo. Reenvío que invalida el link anterior. Link vencido. |
| `AuthSecurityIT` | CSRF obligatorio (sin token y con token falso). Rotación del token CSRF en el login. Mismo mensaje para email inexistente o contraseña incorrecta. Bloqueo y desbloqueo con el reloj. Rate limit por IP (429). Recuperación: cierra las sesiones, link de un solo uso y vencimiento a 1 h. Cuerpos de más de 64 KB (413). |
| `TenantIsolationIT` | **Escenario 6**: un negocio no lee ni escribe datos de otro aunque inyecte `tenantId` en body, query, headers o cookies. Negocio suspendido (403). Sin sesión (401). OWNER sin acceso a administración. Normalización del perfil. Slug provisorio y slug fijo después del onboarding. |
| `CalculatorIT` | **Escenarios 2 y 5**: crear desde plantilla, vista previa, publicar (v1), publicar sin cambios (no crea versión), cambiar precios (el borrador cambia, la v1 queda intacta en la base), v2 e historial. Trigger de inmutabilidad (UPDATE y DELETE rechazados). Validaciones al guardar (inconsistencias ubicadas por regla, propiedades desconocidas, dirección repetida, color sin contraste). Bloqueo optimista entre pestañas. Cotizador vacío no publicable (422). Estados (pausar, reactivar, archivar, restaurar, duplicar). Vista previa con configuración sin guardar y con problemas. **IDOR**: otro negocio recibe 404 en todas las operaciones y no ve el listado. |
| `BusinessLogoIT` | Subir, reemplazar, leer en público (tipo por contenido y caché inmutable), quitar. Solo la referencia en la base. Rechazo de archivos falsos y SVG con scripts. Más de 2 MB (413). Sin CSRF (403). Asset inexistente (404). |
| `WebLayerIT` | Páginas con rutas limpias. Cabeceras de seguridad (CSP sin `unsafe-inline`). Ninguna página con scripts o estilos inline. 404 HTML para páginas y JSON para la API. Errores sin detalles internos. `site-config.js`. Healthcheck. `/actuator/*` y `/api/dev/*` cerrados. Resumen de SUPER_ADMIN. Catálogo público. OpenAPI y Swagger accesibles fuera de prod. |
| `PublicQuoteIT` | **Escenarios 3, 4 y 5**: el formulario público no expone reglas, precios ni ids; el servidor calcula e ignora un total enviado por el cliente; deduplicación (mismo visitante, otro visitante, ventana de 30 min); respuestas inválidas no se guardan; la cotización vieja conserva su importe tras publicar precios nuevos y la base rechaza cambiarlo; consulta con token (sin token o con otro: 404), email al negocio, una por cotización; validaciones de contacto y campo trampa; datos antes del precio; preferencia de aviso; pausado, archivado, borrador y negocio suspendido; PDF (texto verificado con PDFBox) y clic en WhatsApp; límite por IP (429); metadatos para compartir escapados |
| `LeadsIT` | Listado, orden, paginación, búsqueda (nombre, código, teléfono, comodines literales), filtros por estado y fecha, cantidades por estado; estados, notas e historial; CSV (BOM, separador `;`, fórmulas neutralizadas, auditoría); anonimización (borra contacto, notas y texto libre; el importe no cambia; no admite notas nuevas); retención; preferencias |
| `StatsIT` | Totales, conversión, ventas confirmadas, serie diaria completa, ranking de cotizadores, distribución; período por defecto, mensual a 12 meses, límites (400) y sin sesión (401); otro negocio no suma |
| `BillingIT` | **Escenarios 7, 8 y 9**. Plan gratuito por defecto y catálogo público. Límite de cotizadores publicados (publicar, reactivar, republicar). Cupo de cotizaciones con avisos al 80 % y 100 %, sin contar inválidas ni recálculos, nuevo período. Cupo con 20 pedidos simultáneos (no se supera). Funciones por plan (marca, color, CSV). Activación **solo** con pago aprobado (volver del checkout, medio autorizado y pago en proceso no activan). Webhook duplicado, firma inválida, ausente o vieja (401), tipos ignorados. Pago rechazado → gracia → vencimiento al plan Gratis sin borrar datos (el cotizador excedente queda no disponible). Rechazo viejo fuera de orden. Cancelación por el negocio y por el proveedor. Cambio de plan. Conciliación que corrige una notificación perdida. Checkout abandonado. Editar un plan no cambia contratos vigentes. Panel de administración: permisos, negocios, métricas (MRR estimado vs. cobrado), suspensión y reactivación. |
| `PaymentsUnitTest` | Firma de Mercado Pago (manifest de la documentación, minúsculas, partes faltantes, segundos o milisegundos, alteraciones, antigüedad, sin clave). Cliente HTTP contra un servidor simulado (creación con pago pendiente, lectura de cuotas y suscripciones, 404, 5xx sin filtrar el token, sin credenciales). El simulador impide arrancar en producción. |
| `DemoIT` | Las demos se crean una sola vez, publicadas y sin dueño. El formulario público no expone precios. La estimación en vivo calcula en el servidor ($ 108.000, el ejemplo de la landing) sin guardar nada y solo para el negocio demo. Rechazo de datos de contacto (409) en la cotización y en la consulta posterior. Cupo que no se agota y borrado de las cotizaciones de prueba a las 48 h. El negocio demo no aparece en la administración ni en sus métricas. |
| `AccountIT` | Nombre y preferencias de email. Cambio de contraseña: exige la actual, valida la política, cierra las otras sesiones y mantiene la actual, avisa por email. Exportación: ZIP con todos los archivos, datos del propio negocio y **nada** de otro negocio ni secretos (tokens, hashes, contraseñas), auditada. Sin negocio no hay exportación ni baja (403); sin sesión, 401. Baja: contraseña y ELIMINAR obligatorios, cancela el plan pago, email con la fecha, cotizadores fuera del público, panel accesible, cancelación de la baja. Ejecución a los 30 días: borra o anonimiza todo lo del negocio, conserva los pagos, anonimiza al usuario (no puede ingresar), no deja el email en los registros, no toca a otro negocio; las versiones siguen siendo inmutables para el resto; idempotente. |
| `ProductEmailsIT` | Recordatorio de activación solo a quien no publicó, entre el día 2 y el 14, una sola vez y respetando la baja; enlace y encabezado de baja. Los transaccionales no llevan enlace de baja. El enlace da de baja por POST sin sesión (un GET no da de baja; un token inválido da 404). Resumen mensual con los números del mes anterior, solo los primeros días del mes y una vez por mes. |
| `ArchitectureTest` | Sin ciclos entre módulos; `shared` independiente; motor de precios puro; controllers sin repositorios ni entidades |

### E2E (`tests/e2e`, Playwright: móvil Pixel 7 + escritorio 1366×860)
| Recorrido | Verifica |
|---|---|
| Alta completa | Registro → aviso de email sin confirmar → link desde la bandeja de dev (el token se quita de la URL) → ingreso → panel → datos del negocio (WhatsApp normalizado, slug actualizado) → paso 1 completo → salir → panel protegido |
| Validación | Errores accesibles (`aria-invalid` y foco en el primer error) y mensaje genérico ante credenciales inválidas |
| Guía de configuración | Primer ingreso → guía → negocio → objetivo → plantilla recomendada para el rubro → precios propios → publicar → enlace → vista previa con el precio nuevo. Sin scroll horizontal. |
| Constructor desde cero | Nuevo cotizador → pregunta de cantidad → precio por unidad → vista previa en vivo calculada por el servidor → guardar → publicar → listado. Sin scroll horizontal. |
| Publicación incompleta | El editor explica qué falta y lleva a la pestaña Publicación. |
| Cotizador público | El cliente abre el enlace, el servidor marca lo que falta, cotiza ($ 99.000), cambia una respuesta (ticket desactualizado) y recalcula, descarga el PDF, deja sus datos (consentimiento obligatorio) y abre el enlace guardado de la cotización. El negocio ve la consulta, la marca como contactada con una nota y la ve en estadísticas y en el inicio. Sin scroll horizontal. |
| Cotizador pausado | Muestra el aviso y el contacto del negocio, sin formulario. |
| Contratación de un plan | Plan Gratis y consumo en el inicio → "Pasar a Pro" → simulador → volver sin pagar no activa → pago rechazado (se informa) → pago aprobado (plan Pro, límites y pago) → cancelar (conserva hasta fin del período). |
| Límite del plan Gratis | Publicar un segundo cotizador muestra el límite y el enlace a los planes. |
| Administración | Métricas con MRR estimado y cobrado, negocios, planes, integración y conciliación manual. |
| Landing | Demo en vivo calculada por el servidor ($ 108.000 inicial; recalcula al cambiar los m²), cambio de rubro con las pestañas, precios leídos de la base. Sin scroll horizontal. |
| Demo completa | La página pública de la demo muestra el aviso, calcula ($ 99.000) y, en lugar del formulario de contacto, invita a crear un cotizador. |
| Precios, ayuda y portal | Tabla comparativa con los planes de la base, búsqueda en la ayuda que lleva al artículo correcto, portal con los planes. |
| Tu cuenta | Acceso desde el menú (celular) o la barra lateral, nombre, emails de producto, contraseña (error con la actual incorrecta), descarga del ZIP, baja con validación de ELIMINAR, aviso en el inicio y cancelación de la baja. |

**Otros navegadores (Escenario 12):** `E2E_BROWSERS=all npx playwright test` suma Safari de iPhone (WebKit) y Firefox de escritorio (requiere `npx playwright install firefox webkit`). El 14/11/2026 pasaron los 28 recorridos en ambos.

En WebKit, la consola registra "Refused to apply a stylesheet…" **al cerrar** la página de prueba. Se verificó que no aparece mientras la página está abierta (con una espera de 2,5 s antes de terminar): es un mensaje de WebKit al cerrar, no de la app.

Todo test E2E **falla si aparece un error de consola o de JavaScript**, incluidas las violaciones de la CSP. Las respuestas 4xx de la API que forman parte del flujo son la única excepción.

Toda la suite E2E entra desde la misma IP. Por eso la app de pruebas se levanta con `RATE_LIMIT_AUTH_PER_MINUTE=300` (lo hace `playwright.config.js`; si la app ya está corriendo, hay que levantarla con esa variable). En producción el límite sigue en 20 por minuto, y `AuthSecurityIT` verifica el 429.

## Verificaciones manuales de la Fase 1

- Jar empaquetado con el frontend dentro (igual que el `Dockerfile`), ejecutado con perfil `prod`:
  - páginas y recursos servidos desde el classpath;
  - Swagger devuelve 404 y `/api/dev` está denegado;
  - el host del portal muestra el portal;
  - CSRF exigido;
  - una caída del SMTP no rompe el registro (se registra enmascarado);
  - la app **no arranca** con `MAIL_PROVIDER=log`.
- `docker build` y `docker compose up`: verificados en la Fase 5 (ver abajo).

## Verificaciones con Docker (Fase 5, 14/11/2026)

Con el stack de `infra/docker-compose.yml` (perfil `prod`, Mailpit y servicio de backups). El detalle está en [DEPLOYMENT.md](DEPLOYMENT.md#estado-14112026).

- Build de las dos imágenes.
- Arranque en limpio (V1–V6, demos y super admin).
- Recorrido real por la API: registro, verificación con el email entregado por SMTP, cotizador, consulta y logo.
- Reinicio y recreación de contenedores sin pérdida de datos.
- Backup con prueba de restauración automática.
- Restauración completa desde volúmenes vacíos.
- Smoke test de las páginas nuevas servidas desde la imagen.

## Pendientes por fase


