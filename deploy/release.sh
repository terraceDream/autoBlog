#!/usr/bin/env bash
set -euo pipefail
function docker() { sudo docker "$@"; }
cd /home/ubuntu/autoblog
exec 9>/home/ubuntu/autoblog/deploy.lock
flock -n 9 || { echo 'Another deployment is active'; exit 1; }
test -s server.env
sudo test -s /etc/nginx/autoblog.htpasswd
release="$(date -u +%Y%m%d%H%M%S)"
docker build -f incoming/Dockerfile -t "autoblog:$release" incoming
mkdir -p /var/www/autoblog/releases
tar -xzf incoming/frontend.tar.gz -C /var/www/autoblog/releases --one-top-level="$release"
previous=$(docker inspect autoblog --format '{{.Config.Image}}' 2>/dev/null || true)
if [ -n "$previous" ]; then sudo bash /home/ubuntu/autoblog/backup.sh; fi
docker rm -f autoblog 2>/dev/null || true
start() {
  docker run -d --name autoblog --restart unless-stopped --network festival_default \
    --memory 900m --cpus 1.25 --pids-limit 256 \
    --log-opt max-size=10m --log-opt max-file=3 \
    -p 127.0.0.1:8081:8080 --env-file server.env \
    -v /home/ubuntu/autoblog/state:/state "$1"
}
start "autoblog:$release"
healthy=false
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8081/api/health >/dev/null; then healthy=true; break; fi
  sleep 2
done
if [ "$healthy" != true ]; then
  docker logs --tail 40 autoblog
  docker rm -f autoblog
  if [ -n "$previous" ]; then start "$previous"; fi
  echo 'Deployment failed; previous image restored. Review migrations before retry.'
  exit 1
fi
ln -sfn "/var/www/autoblog/releases/$release" /var/www/autoblog/current-next
mv -Tf /var/www/autoblog/current-next /var/www/autoblog/current
echo "Deployed autoblog:$release"
