#!/bin/sh
# Backup de Climind SaaS: base de datos (pg_dump, formato custom) + archivos subidos (logos).
#
# Variables:
#   PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD   conexión a PostgreSQL (las estándar de libpq)
#   STORAGE_DIR            carpeta de archivos de la app (montada en solo lectura). Default: /data/storage
#   BACKUP_DIR             destino local. Default: /backups
#   BACKUP_RETENTION_DAYS  días que se conservan los backups (local y remoto). Default: 14
#   RCLONE_REMOTE          destino fuera del VPS (p. ej. "r2:climind-backups/cotiza"). Vacío = solo local.
#                          La configuración de rclone se pasa con RCLONE_CONFIG_* (ver docs/DEPLOYMENT.md).
#
# Cada ejecución genera tres archivos con la misma marca de tiempo (UTC):
#   db-AAAAMMDDTHHMMSSZ.dump, storage-AAAAMMDDTHHMMSSZ.tar.gz y AAAAMMDDTHHMMSSZ.sha256
set -eu

STORAGE_DIR="${STORAGE_DIR:-/data/storage}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
RETENTION="${BACKUP_RETENTION_DAYS:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

log() { echo "[backup $(date -u +%H:%M:%S)] $*"; }

mkdir -p "$BACKUP_DIR"
cd "$BACKUP_DIR"

log "Base de datos ${PGDATABASE:-?} en ${PGHOST:-?}"
# Se escribe primero con otro nombre: un backup cortado a la mitad nunca queda con el nombre final.
pg_dump --format=custom --compress=6 --no-owner --no-privileges --file="db-$STAMP.dump.partial"
mv "db-$STAMP.dump.partial" "db-$STAMP.dump"

if [ -d "$STORAGE_DIR" ]; then
  log "Archivos de $STORAGE_DIR"
  tar -czf "storage-$STAMP.tar.gz.partial" -C "$STORAGE_DIR" .
  mv "storage-$STAMP.tar.gz.partial" "storage-$STAMP.tar.gz"
else
  log "AVISO: no existe $STORAGE_DIR; el backup no incluye archivos"
fi

if [ -f "storage-$STAMP.tar.gz" ]; then
  sha256sum "db-$STAMP.dump" "storage-$STAMP.tar.gz" > "$STAMP.sha256"
else
  sha256sum "db-$STAMP.dump" > "$STAMP.sha256"
fi
# Verificación inmediata: el dump se puede leer (lista su contenido sin restaurar).
pg_restore --list "db-$STAMP.dump" > /dev/null
log "Listo: $(du -h "db-$STAMP.dump" | cut -f1) de base de datos"

if [ -n "${RCLONE_REMOTE:-}" ]; then
  log "Copiando fuera del servidor a $RCLONE_REMOTE"
  rclone copy "$BACKUP_DIR" "$RCLONE_REMOTE" --include "*$STAMP*" --no-traverse
  rclone delete "$RCLONE_REMOTE" --min-age "${RETENTION}d" || log "AVISO: no se pudo aplicar la retención remota"
fi

# Retención local.
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'db-*.dump' -o -name 'storage-*.tar.gz' -o -name '*.sha256' \) \
  -mtime +"$RETENTION" -print -delete | sed 's/^/[backup] borrado por antigüedad: /'
find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.partial' -mmin +120 -delete

echo "$STAMP" > "$BACKUP_DIR/LATEST"
log "Backup $STAMP completo"
