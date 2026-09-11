#!/usr/bin/env bash
set -euo pipefail
exec 9>/run/autoblog-cert-renew.lock
flock -n 9 || exit 0
docker run --rm -v /etc/letsencrypt-autoblog:/etc/letsencrypt \
  -v /var/www/autoblog-acme:/var/www/autoblog-acme \
  certbot/certbot:v5.4.0 renew --quiet
nginx -t
systemctl reload nginx
