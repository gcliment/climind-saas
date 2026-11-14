# Reglas de negocio y motor de cotizaciones

> Estado: el motor de precios está **implementado y probado** (Fase 2: `backend/src/main/java/com/climind/saas/pricing`, 39 tests propios + 5 con las plantillas reales). Las reglas de cuentas, planes y consultas se listan al final y se marcan según su fase.

## Principios

1. **El backend es la fuente de verdad de todos los importes.** El navegador muestra lo que calcula el servidor. La demo de la landing usa precios ficticios marcados como "ejemplo" y no se usa para cotizaciones reales.
2. **Dinero con `BigDecimal`**, escala y redondeo explícitos. Nunca `float` ni `double`.
3. **Reglas tipadas y validadas.** No hay fórmulas libres, ni `eval`, ni JavaScript de usuarios.
4. **Las cotizaciones históricas no cambian:** cada cálculo referencia una versión inmutable y guarda un snapshot.
5. **Una estimación no es una factura** ni un comprobante fiscal.

## Configuración de un cotizador (JSONB, `schemaVersion: 1`)

```json
{
  "schemaVersion": 1,
  "currency": "ARS",
  "questions": [
    {"key": "tipo", "type": "SINGLE_CHOICE", "label": "¿Qué tipo de limpieza necesitás?", "required": true,
     "help": "", "options": [{"key": "basica", "label": "Limpieza básica"}, {"key": "profunda", "label": "Limpieza profunda"}]},
    {"key": "m2", "type": "NUMBER", "label": "Superficie", "unit": "m²", "min": 1, "max": 5000, "integer": false, "required": true},
    {"key": "ambientes", "type": "QUANTITY", "label": "Cantidad de ambientes", "min": 1, "max": 50},
    {"key": "extras", "type": "MULTI_CHOICE", "label": "Servicios adicionales", "options": [{"key": "vidrios", "label": "Vidrios"}]},
    {"key": "urgente", "type": "YES_NO", "label": "¿Lo necesitás en 24 h?"},
    {"key": "detalle", "type": "TEXT", "label": "Contanos algún detalle", "maxLength": 1000},
    {"key": "fecha", "type": "DATE", "label": "Fecha deseada"},
    {"key": "aberturas", "type": "QUANTITY", "label": "Ventanas", "visibleIf": [{"question": "tipo", "op": "EQUALS", "value": "profunda"}]}
  ],
  "pricing": {
    "rules": [
      {"id": "r1", "type": "OPTION_PRICE", "question": "tipo", "label": "Servicio", "prices": {"basica": "50000", "profunda": "80000"}},
      {"id": "r2", "type": "PER_UNIT", "question": "m2", "label": "Superficie", "unitPrice": "3000",
       "tiers": [{"from": "100", "unitPrice": "2500"}]},
      {"id": "r3", "type": "OPTION_PER_UNIT", "question": "menu", "quantityQuestion": "invitados", "prices": {"clasico": "5000"}},
      {"id": "r4", "type": "FIXED", "label": "Vidrios", "amount": "15000", "when": [{"question": "extras", "op": "INCLUDES", "value": "vidrios"}]},
      {"id": "r5", "type": "SURCHARGE_PERCENT", "label": "Urgencia", "percent": "20", "when": [{"question": "urgente", "op": "IS_TRUE"}]},
      {"id": "r6", "type": "DISCOUNT_PERCENT", "label": "Promoción", "percent": "10"}
    ],
    "minimumTotal": "30000",
    "tax": {"mode": "FINAL", "rate": "21", "label": "IVA"},
    "rounding": {"scale": 0, "mode": "HALF_UP"}
  },
  "commercial": {
    "disclaimer": "Este importe es una estimación. El precio final está sujeto a confirmación del negocio.",
    "validityDays": 15,
    "leadCapture": "AFTER_ESTIMATE",
    "whatsappTemplate": "Hola, realicé una cotización desde su página. Me interesa {servicio} y el precio estimado es de {total}. Mi código de consulta es {codigo}."
  }
}
```

Los importes viajan como **strings decimales** para no perder precisión en JSON.

## Tipos de pregunta

| Tipo | Valor de respuesta | ¿Afecta el precio? |
|---|---|---|
| `SINGLE_CHOICE` | key de una opción | sí |
| `MULTI_CHOICE` | lista de keys | sí |
| `QUANTITY` | entero entre `min` y `max` | sí |
| `NUMBER` | decimal (o entero si `integer`) entre `min` y `max`, con `unit` visible | sí |
| `YES_NO` | boolean | sí |
| `TEXT` | texto (máx. `maxLength`, por defecto 1000) | **no** |
| `DATE` | `AAAA-MM-DD`, no anterior a hoy | **no** (MVP) |

## Tipos de regla

| Tipo | Cálculo | Ejemplo |
|---|---|---|
| `FIXED` | `amount` | Servicio básico $50.000 |
| `OPTION_PRICE` | precio de la opción elegida (suma si es `MULTI_CHOICE`) | Básica $50.000 / Profunda $80.000 |
| `PER_UNIT` | `cantidad × unitPrice`; con `tiers`, se usa el precio del tramo más alto cuyo `from` ≤ cantidad (precio por volumen, no escalonado) | $3.000/m²; desde 100 m², $2.500/m² |
| `OPTION_PER_UNIT` | `cantidad(quantityQuestion) × precio(opción)` | Menú clásico $5.000 por invitado |
| `SURCHARGE_PERCENT` | `subtotal × percent / 100` | Urgencia +20 % |
| `DISCOUNT_PERCENT` | `(subtotal + recargos) × percent / 100` | Promoción 10 % |
| `minimumTotal` | si el total antes de impuestos es menor, se suma una línea "Ajuste a precio mínimo" | Nunca menos de $30.000 |

Una regla con `when` se aplica solo si **todas** sus condiciones se cumplen.

## Orden de aplicación (fijo)

1. **Conceptos base**: `OPTION_PRICE`, `PER_UNIT`, `OPTION_PER_UNIT` y `FIXED`, en el orden configurado. Su suma es el **subtotal**.
2. **Recargos porcentuales**, sobre el subtotal.
3. **Descuentos porcentuales**, sobre el subtotal más los recargos. El total nunca puede quedar negativo.
4. **Mínimo**, aplicado al total antes de impuestos.
5. **Impuesto**:
   - `FINAL`: precios con impuestos incluidos; no se suma nada.
   - `EXCLUDED`: muestra la leyenda "Precios sin impuestos" y no suma nada.
   - `ADDED`: suma `rate %` como línea aparte.
6. **Redondeo final** según `rounding` (por defecto 0 decimales, HALF_UP).

Cada línea se calcula con escala 2 y HALF_UP. El total redondeado es lo que se muestra y se guarda.

## Condiciones (`when` y `visibleIf`)

- Operadores: `EQUALS`, `NOT_EQUALS`, `INCLUDES` (multi), `IS_TRUE`, `IS_FALSE`, `GT`, `GTE`, `LT`, `LTE`.
- Lista con semántica AND. Sin OR ni anidamiento en el MVP.
- `visibleIf` solo puede referirse a preguntas **anteriores**, lo que impide ciclos.
- Las preguntas ocultas no se validan, no se guardan y no participan del cálculo (sus reglas se omiten).

## Validación de la configuración (al guardar y al publicar)

- `key` e `id` únicos; deben respetar `^[a-z][a-z0-9_]{0,39}$`.
- Toda referencia (`question`, `quantityQuestion` y keys de opciones en `prices` y `when`) tiene que existir.
- Tipos compatibles:
  - `PER_UNIT` → `QUANTITY`/`NUMBER`.
  - `OPTION_PRICE` → `SINGLE_CHOICE`/`MULTI_CHOICE`.
  - `OPTION_PER_UNIT` → `SINGLE_CHOICE` + `QUANTITY`/`NUMBER`.
  - `TEXT` y `DATE` no pueden tener reglas.
- `min ≤ max`.
- Montos entre 0 y 10.000.000.000.
- Descuentos de 0 a 100 %, recargos de 0 a 500 %, impuesto de 0 a 100 %.
- Tramos con `from` estrictamente creciente.
- Límites: hasta 40 preguntas, 30 opciones por pregunta y 60 reglas. Textos: etiquetas de hasta 200 caracteres y ayudas de hasta 500.
- Para publicar hace falta al menos una pregunta y al menos una regla de concepto base.

## Validación de las respuestas (en cada cálculo público)

- Las preguntas requeridas y visibles deben estar respondidas.
- Los valores tienen que estar dentro de rango y las opciones deben existir.
- Las claves desconocidas se rechazan (`400`).
- Una simulación inválida **no consume cupo**.

## Versionado

- `calculators.draft_config` se edita libremente.
- **Publicar** crea `calculator_versions(version_no = n+1)` con la configuración validada. Esa fila es **inmutable** (lo hace cumplir un trigger).
- Cada simulación guarda `calculator_version_id`, las respuestas, el resultado completo (líneas, totales, moneda, leyenda, vigencia) y los textos comerciales. **Nunca** se recalcula una cotización histórica.

## Cuentas y sesiones (implementado en la Fase 1)

| Regla | Valor |
|---|---|
| Contraseña | 10 caracteres como mínimo, 72 bytes como máximo; no puede ser común ni contener el email |
| Verificación de email | Obligatoria para ingresar; el enlace vence a las 24 h y se usa una sola vez; reenviar invalida el anterior |
| Recuperación | El enlace vence en 1 h y es de un solo uso; al cambiar la contraseña se cierran todas las sesiones y se avisa por email |
| Bloqueo | 5 intentos fallidos = 15 min; cada bloqueo siguiente dura el doble, hasta 24 h |
| Límites por email | Hasta 5 emails de verificación o recuperación por dirección y por hora |
| Sesión | 7 días de inactividad (`SESSION_TIMEOUT`) |
| Registro | Crea usuario + negocio ("Negocio de {nombre}") + membresía OWNER + perfil y configuración |
| Slug público | `nombre-xxxxx` (sufijo aleatorio). Acompaña al nombre comercial hasta completar el onboarding; después queda fijo |
| WhatsApp | Se guarda en formato wa.me (solo dígitos con código de país, de 10 a 15) |

## Simulación, consulta y venta (Fase 3, implementado)

- **Simulación (cotización)**: el visitante calcula un precio con la versión publicada y el servidor la guarda. Solo se guarda si las respuestas son válidas: los intentos fallidos no cuentan. Si el mismo visitante repite las mismas respuestas en la misma versión dentro de los 30 minutos, es la misma cotización (mismo código y enlace). El visitante se reconoce con una cookie anónima `cotiza_vid` (HttpOnly, 1 día, solo para `/api/v1/public/`); sin cookie no se deduplica. En la Fase 4 cada cotización nueva consume cupo del plan.
- **Clic en WhatsApp**: se registra como intención de contacto, **no** como consulta (no sabemos si el mensaje se envió).
- **Cuándo se piden los datos**: después del precio (por defecto y en todas las plantillas) o antes, si el negocio lo elige en la pestaña Publicación. En ese caso el servidor exige los datos para devolver el precio.
- **Código y enlace**: el código `COT-XXXXXX` (sin 0/O ni 1/I) viaja en el mensaje de WhatsApp y no da acceso. La cotización se lee con código + token (`/cotizacion/{código}#t={token}`).
- **Teléfonos**: se normalizan al formato de wa.me. En Argentina se aceptan "11 2345-6789", "011 15 2345-6789" o "+54 11 …", y se guarda `549` + área + número.
- **Consulta**: el visitante deja sus datos (nombre y WhatsApp obligatorios; email y comentarios opcionales) con consentimiento explícito.
- **Venta confirmada**: solo la registra el negocio a mano (estado `CONVERTIDA`). Una consulta **nunca** cuenta como venta de forma automática.
- Estados de una consulta: `NUEVA → CONTACTADA → EN_NEGOCIACIÓN → CONVERTIDA | PERDIDA`, con historial. El negocio puede pasar de cualquier estado a cualquier otro; cada cambio y cada nota quedan en la línea de tiempo.
- **Aviso por email** al negocio por cada consulta nueva (nunca por cotizaciones anónimas), al email de contacto o, si no hay, al del dueño. Se desactiva en Mi negocio.
- **Protección de datos**: el negocio puede eliminar los datos personales de una consulta (nombre, contacto, comentarios, notas y respuestas de texto libre). Además, todas las noches se anonimizan las consultas más viejas que el plazo de retención del negocio (24 meses por defecto, configurable de 1 a 120). Quedan el código, el importe y el estado.

## Estadísticas (Fase 3)

| Indicador | Definición |
|---|---|
| Cotizaciones | Cotizaciones guardadas en el período (ya deduplicadas) |
| Consultas | Consultas recibidas en el período |
| Conversión | Cotizaciones del período que terminaron en consulta ÷ cotizaciones del período. Sin cotizaciones no se muestra (no se informa un 0 %) |
| Ventas confirmadas | Consultas del período que el negocio marcó como `CONVERTIDA` |
| Abrieron WhatsApp | Cotizaciones del período en las que el cliente tocó el botón de WhatsApp |
| Importe mediano y promedio | Sobre los totales de las cotizaciones del período |

Período máximo de 12 meses. La serie es diaria hasta 92 días y mensual después. Las fechas se cuentan en la zona horaria del negocio.

## Planes y cuotas (Fase 4, implementado)

- Planes y límites en base de datos (nunca en el frontend). Valores iniciales sugeridos:

  | Plan | Precio | Cotizadores publicados | Simulaciones por mes |
  |---|---|---|---|
  | GRATIS | — | 1 | 30 |
  | PRO | $9.900/mes | 3 | 300 |
  | NEGOCIO | $19.900/mes | 10 | 1.500 |

- El cupo se verifica en el backend con un incremento atómico condicionado. El consumo se cuenta por período de facturación.
- Al llegar al límite:
  - el cotizador público muestra "no disponible temporalmente" con el contacto del negocio;
  - el panel ofrece pasar a otro plan;
  - hay avisos al 80 % y al 100 %.
- Pago fallido:
  - `PAST_DUE` con 7 días de gracia.
  - Después, `EXPIRED` con los límites del plan GRATIS. Los cotizadores que exceden el cupo quedan no disponibles (no se borran) y el dueño elige cuáles mantener.
- **Nunca se borran datos** por vencimiento o cancelación.
- Un plan pago se activa **solo** con la confirmación servidor a servidor de Mercado Pago, nunca por volver de la URL de checkout.

### Detalle de la implementación

- **Plan vigente**: si una suscripción paga da beneficios hoy, rigen los valores **que se contrataron** (copia en la suscripción); si no, rige el plan gratuito con sus valores actuales. Editar un plan nunca cambia contratos vigentes.
- **Qué consume cupo**: solo una cotización nueva y válida. No consumen: respuestas inválidas, recalcular lo mismo dentro de 30 minutos, la vista previa del constructor.
- **Período**: plan gratuito, mes calendario (zona del negocio). Plan pago, el período pagado (desde la fecha del cobro).
- **Cotizadores publicados**: se controla al publicar o reactivar. Si después de bajar de plan hay más publicados que los permitidos, siguen visibles los más antiguos y el resto muestra "no disponible" con el contacto del negocio (no se despublica ni se borra nada).
- **Funciones por plan**: exportación CSV, color propio en el cotizador y sin "Creado con Climind Cotiza" (página y PDF), aviso por email de consultas.
- **Cambio de plan** (subir o bajar entre pagos): es una contratación nueva. Al aprobarse su primer pago, la anterior se da de baja en el proveedor. Sin prorrateo.
- **Cancelación**: baja en el proveedor (no hay más cobros) y beneficios hasta el fin del período pagado.
- **Checkout abandonado**: se descarta a las 48 h (o cuando el negocio inicia otro).

### Mapeo de estados de Mercado Pago

| Mercado Pago | Interno |
|---|---|
| Suscripción `pending` | `PENDING` (sin beneficios) |
| Suscripción `authorized` | Se espera el primer pago aprobado (si estaba `PAUSED`, vuelve a `ACTIVE`) |
| Suscripción `paused` | `PAUSED` (sin beneficios) |
| Suscripción `cancelled` | `CANCELED` (beneficios hasta fin del período pagado) |
| Pago `approved` | Pago `APPROVED`; suscripción `ACTIVE` y período +1 mes (nunca acorta uno ya pagado) |
| Pago `rejected` | Pago `REJECTED`; si estaba `ACTIVE` y el rechazo no es más viejo que el último pago: `PAST_DUE` con 7 días de gracia |
| Pago `refunded` / `charged_back` / `cancelled` | `REFUNDED` / `CANCELLED` (quedan registrados) |
| Otros (`pending`, `in_process`…) | Pago `PENDING`, sin cambios de beneficios |

Gracia vencida → `EXPIRED` (baja en el proveedor y plan Gratis). Mercado Pago reintenta cada cuota hasta 4 veces y da de baja la suscripción después de 3 cuotas rechazadas; esa baja llega como notificación.

### Webhooks y conciliación

- Firma `x-signature` validada con HMAC-SHA256 sobre `id:[data.id];request-id:[x-request-id];ts:[ts];` y la clave secreta de Webhooks; se rechazan notificaciones con más de 15 minutos de antigüedad.
- Idempotencia por id de notificación: una repetida no se procesa.
- Nunca se usa el contenido de la notificación: se consulta el recurso en la API (independiente del orden de llegada).
- Si el procesamiento falla se responde 500 (el proveedor reintenta) y queda registrado.
- **Conciliación** cada hora: consulta las suscripciones abiertas y sus cobros; cada diferencia corregida queda registrada y se ve en el panel de administración.
