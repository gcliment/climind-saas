# Servicio de backups de Climind SaaS: pg_dump/pg_restore 17 + rclone para copiar fuera del VPS.
# Contexto de build: infra/backup/
FROM postgres:17-alpine
RUN apk add --no-cache rclone tzdata coreutils
COPY backup.sh restore.sh restore-test.sh entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/*.sh && mkdir -p /backups
ENV TZ=America/Argentina/Buenos_Aires \
    BACKUP_DIR=/backups \
    STORAGE_DIR=/data/storage \
    BACKUP_RETENTION_DAYS=14 \
    BACKUP_HOUR=3 \
    BACKUP_VERIFY=1
VOLUME ["/backups"]
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
