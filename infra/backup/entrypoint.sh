#!/bin/sh
# Servicio de backups: corre backup.sh todos los días a BACKUP_HOUR (hora de Argentina) y, si BACKUP_VERIFY=1,
# prueba la restauración del backup recién hecho en una base temporal.
#   docker run ... climind-backup            → queda programado
#   docker run ... climind-backup now        → un backup inmediato y termina
set -eu

run() {
  /usr/local/bin/backup.sh
  if [ "${BACKUP_VERIFY:-1}" = "1" ]; then
    /usr/local/bin/restore-test.sh
  fi
}

if [ "${1:-}" = "now" ]; then
  run
  exit 0
fi
if [ "${1:-}" != "" ]; then
  exec "$@"
fi

HOUR="${BACKUP_HOUR:-3}"
echo "[backup] Programado todos los días a las ${HOUR}:00 (${TZ:-UTC})"
while true; do
  now=$(date +%s)
  next=$(date -d "today ${HOUR}:00" +%s)
  [ "$next" -gt "$now" ] || next=$(date -d "tomorrow ${HOUR}:00" +%s)
  sleep $((next - now))
  run || echo "[backup] ERROR: el backup falló (revisá los logs; se reintenta mañana)"
done
