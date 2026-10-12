#!/bin/sh
set -e
umask 077
export TZ=Asia/Seoul
BACKUP_DIR=/opt/jango-yebo/backups
STAMP=$(date +%Y%m%d_%H%M)
docker exec jango-postgres pg_dump -U jango -d jango --clean --if-exists \
  | gzip > "$BACKUP_DIR/jango_$STAMP.sql.gz"
find "$BACKUP_DIR" -name 'jango_*.sql.gz' -mtime +7 -delete
