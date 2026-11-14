# database/

Las migraciones viven junto al código que las valida: [`backend/src/main/resources/db/migration`](../backend/src/main/resources/db/migration) (Flyway, V1 a V6).

- Modelo, convenciones y migraciones: [docs/DATABASE.md](../docs/DATABASE.md).
- Backups diarios, prueba de restauración automática y procedimiento de restauración (probado con Docker el 14/11/2026): [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md#backups). Los scripts están en [`infra/backup/`](../infra/backup).
- Datos de demostración independientes de los clientes reales: el negocio ficticio de las demos públicas se crea al arrancar desde las plantillas (`APP_DEMO_ENABLED`; ver [docs/BUSINESS_RULES.md](../docs/BUSINESS_RULES.md#demos-públicas)).
