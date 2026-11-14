#!/bin/sh
# Restauración de un backup de Climind SaaS.
#
#   restore.sh [MARCA]            restaura en la base configurada (PGDATABASE) y en STORAGE_DIR
#   restore.sh MARCA --db-only    solo la base de datos
#
# MARCA es la marca de tiempo del backup (p. ej. 20261114T060000Z). Sin MARCA usa el último (archivo LATEST).
# IMPORTANTE: detené la app antes de restaurar (en EasyPanel: Stop en el servicio "app") y volvé a iniciarla al
# terminar. La restauración REEMPLAZA los datos actuales. Para probar un backup sin tocar producción usá
# restore-test.sh.
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
STORAGE_DIR="${STORAGE_DIR:-/data/storage}"
STAMP="${1:-$(cat "$BACKUP_DIR/LATEST")}"
MODE="${2:-}"

log() { echo "[restore $(date -u +%H:%M:%S)] $*"; }

DUMP="$BACKUP_DIR/db-$STAMP.dump"
FILES="$BACKUP_DIR/storage-$STAMP.tar.gz"
[ -f "$DUMP" ] || { echo "No existe $DUMP"; exit 1; }

log "Verificando sumas de control"
(cd "$BACKUP_DIR" && sha256sum -c "$STAMP.sha256")

if [ "${RESTORE_CONFIRM:-}" != "si" ]; then
  echo "Esto reemplaza los datos de la base ${PGDATABASE:-?} en ${PGHOST:-?}."
  echo "Para continuar, ejecutá con RESTORE_CONFIRM=si"
  exit 2
fi

log "Restaurando la base de datos desde $DUMP"
# --clean --if-exists: borra los objetos existentes antes de recrearlos. Una sola transacción: todo o nada.
pg_restore --clean --if-exists --no-owner --no-privileges --single-transaction --exit-on-error \
  --dbname="$PGDATABASE" "$DUMP"

if [ "$MODE" != "--db-only" ] && [ -f "$FILES" ]; then
  log "Restaurando archivos en $STORAGE_DIR"
  mkdir -p "$STORAGE_DIR"
  find "$STORAGE_DIR" -mindepth 1 -delete
  tar -xzf "$FILES" -C "$STORAGE_DIR"
fi

log "Restauración $STAMP completa. Iniciá la app: Flyway valida el esquema al arrancar."
