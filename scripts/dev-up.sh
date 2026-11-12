#!/usr/bin/env bash
# Start the CareNexus local development stack (DEV ONLY, not a deployment).
#
#   scripts/dev-up.sh            # postgres + livekit (dev mode) + API, all in Docker
#   scripts/dev-up.sh --api-local  # postgres + livekit in Docker, API on the host ('local' profile)
#   scripts/dev-up.sh --down     # stop the stack (data volume is kept)
#
# The web app is started separately:  cd frontend && npx expo start --web
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required (Docker Desktop or a compatible engine)." >&2
  exit 1
fi

MODE="${1:-}"

if [[ "$MODE" == "--down" ]]; then
  docker compose down
  exit 0
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env from .env.example (local development values only)."
fi

case "$MODE" in
  "")
    docker compose up -d --build postgres-db livekit carenexus-api
    echo
    echo "API:     http://localhost:9000  (health: /actuator/health)"
    echo "LiveKit: ws://localhost:7880    (dev mode, public dev key pair)"
    ;;
  --api-local)
    docker compose up -d postgres-db livekit
    echo "Starting the API on the host with the 'local' profile (Ctrl+C to stop)..."
    cd backend/carenexus-api
    exec ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local
    ;;
  *)
    echo "Unknown option: $MODE (use --api-local or --down)" >&2
    exit 2
    ;;
esac

echo "Web:     cd frontend && npx expo start --web"
