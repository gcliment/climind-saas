#!/bin/sh
# Prueba de restauración: restaura el último backup (o MARCA) en una base TEMPORAL y verifica que esté completo,
# sin tocar la base de producción. Conviene correrla después de cada backup (BACKUP_VERIFY=1) o al menos una vez
# por semana. Sale con código distinto de 0 si algo falla.
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
STAMP="${1:-$(cat "$BACKUP_DIR/LATEST")}"
TEST_DB="${RESTORE_TEST_DB:-climind_restore_test}"
DUMP="$BACKUP_DIR/db-$STAMP.dump"
FILES="$BACKUP_DIR/storage-$STAMP.tar.gz"

log() { echo "[restore-test $(date -u +%H:%M:%S)] $*"; }
fail() { echo "[restore-test] FALLÓ: $*"; exit 1; }

[ -f "$DUMP" ] || fail "no existe $DUMP"
(cd "$BACKUP_DIR" && sha256sum -c "$STAMP.sha256" > /dev/null) || fail "sumas de control"

log "Creando la base temporal $TEST_DB"
psql -v ON_ERROR_STOP=1 -q -d "$PGDATABASE" -c "DROP DATABASE IF EXISTS $TEST_DB" -c "CREATE DATABASE $TEST_DB"
trap 'psql -q -d "$PGDATABASE" -c "DROP DATABASE IF EXISTS $TEST_DB" > /dev/null 2>&1 || true' EXIT

log "Restaurando $DUMP"
pg_restore --no-owner --no-privileges --single-transaction --exit-on-error --dbname="$TEST_DB" "$DUMP" \
  || fail "pg_restore"

q() { psql -v ON_ERROR_STOP=1 -At -d "$TEST_DB" -c "$1"; }

VERSION="$(q "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
FAILED="$(q "SELECT count(*) FROM flyway_schema_history WHERE NOT success")"
[ -n "$VERSION" ] || fail "no hay historial de migraciones"
[ "$FAILED" = "0" ] || fail "hay migraciones fallidas en el backup"
log "Esquema en la versión V$VERSION"

for table in users tenants business_profiles calculators calculator_versions quote_simulations quote_requests \
             subscription_plans tenant_subscriptions payment_transactions audit_logs; do
  COUNT="$(q "SELECT count(*) FROM $table")" || fail "falta la tabla $table"
  log "  $table: $COUNT filas"
done
PLANS="$(q "SELECT count(*) FROM subscription_plans WHERE is_free")"
[ "$PLANS" -ge 1 ] || fail "no hay plan gratuito (¿backup vacío?)"

# Los triggers de inmutabilidad viajan con el backup.
q "SELECT 1 FROM pg_trigger WHERE tgname = 'trg_calculator_versions_immutable'" | grep -q 1 \
  || fail "falta el trigger de versiones inmutables"

if [ -f "$FILES" ]; then
  ENTRIES="$(tar -tzf "$FILES" | grep -vc '/$' || true)"
  log "Archivos en el backup: $ENTRIES"
fi

log "OK: el backup $STAMP se restaura completo"
