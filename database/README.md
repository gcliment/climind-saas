# database/

Las migraciones viven junto al código que las valida: [`backend/src/main/resources/db/migration`](../backend/src/main/resources/db/migration) (Flyway).

- Modelo, convenciones y próximas migraciones: [docs/DATABASE.md](../docs/DATABASE.md).
- Backups y restore: [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md#backups). Los scripts automatizados y la prueba de restore llegan en la Fase 5.
- Datos de demostración (negocios y cotizadores ficticios, independientes de clientes reales): Fase 2, junto con las plantillas.
