#!/usr/bin/env bash
set -euo pipefail
umask 077
directory=/home/ubuntu/autoblog/backups
mkdir -p "$directory"
stamp=$(date -u +%Y%m%d%H%M%S)
docker exec festival-db pg_dump -U fstvusr -d fstv -n autoblog -Fc > "$directory/autoblog-$stamp.dump.tmp"
mv "$directory/autoblog-$stamp.dump.tmp" "$directory/autoblog-$stamp.dump"
find "$directory" -maxdepth 1 -type f -name 'autoblog-*.dump' -mtime +14 -delete
