#!/usr/bin/env bash
# One-time setup of the lab on the Mac (192.168.0.19). Run from the repository root:
#   ./setup/mac-setup.sh
# Requires Docker Desktop for Mac running (Settings > Resources: >= 4 CPUs, >= 6 GB memory).
set -euo pipefail
cd "$(dirname "$0")/.."

command -v docker >/dev/null || { echo "ERROR: docker not found. Install Docker Desktop for Mac and start it."; exit 1; }
docker info >/dev/null 2>&1 || { echo "ERROR: Docker Desktop is not running. Start it and retry."; exit 1; }
docker compose version >/dev/null || { echo "ERROR: 'docker compose' (v2) is required."; exit 1; }

[ -f .env ] || { cp .env.example .env; echo "Created .env from .env.example"; }

echo ">>> Building images and starting Jenkins + ShopLite API (first build takes a few minutes)"
docker compose up -d --build jenkins perf-app

echo ">>> Waiting for Jenkins to start"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/login || true)
  [ "$code" = "200" ] && break
  sleep 5
done

echo ">>> Health checks"
curl -fsS http://localhost:8081/health && echo "  <- ShopLite API (from the Mac)"
docker exec jenkins curl -fsS http://perf-app:8080/health && echo "  <- ShopLite API (from inside Jenkins)"
docker exec jenkins java -version 2>&1 | head -1
docker exec jenkins bash -c 'echo "JMETER_HOME=$JMETER_HOME"; jmeter --version 2>/dev/null | grep -i "5\.6" || true'

IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo 192.168.0.19)
echo
echo "Jenkins is up:  http://$IP:8080"
if docker exec jenkins test -f /var/jenkins_home/secrets/initialAdminPassword; then
  echo "Initial admin password (Unlock Jenkins screen):"
  docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
fi
echo "ShopLite API:   http://$IP:8081/health"
echo "Next: docs/03-prerequisites-and-installation.md, section 'First-time Jenkins configuration'."
