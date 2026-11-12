# CareNexus web app: the Expo static web export, served by nginx with a single-page-app fallback and security
# headers (frontend/docker/).
#
# EXPO_PUBLIC_* values are inlined into the JavaScript bundle at BUILD time, so build one image per environment:
#
#   docker build -t carenexus-web \
#     --build-arg EXPO_PUBLIC_API_URL=https://api.example.com \
#     --build-arg EXPO_PUBLIC_SUPPORT_EMAIL=support@example.com \
#     frontend
#
# Read when the container STARTS (no rebuild needed):
#   CSP_CONNECT_SRC  space-separated origins the browser may connect to besides the page's own origin: the API
#                    origin and the LiveKit URLs (https:// and wss:// of the LiveKit project). The default suits
#                    the local docker compose stack only.
#
# The container listens on 8081 as an unprivileged user and serves plain HTTP: put TLS (and HSTS) in front of it
# at the load balancer or reverse proxy. docker-compose.yml runs it as the carenexus-web service.

# ---- Stage 1: static export -------------------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app
ENV CI=1 \
    EXPO_NO_TELEMETRY=1

# Dependencies first, so source changes reuse this layer.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# Only what the export needs: no host node_modules, no .env files, no e2e output.
COPY app.json tsconfig.json expo-types.d.ts ./
COPY assets ./assets
COPY src ./src

ARG EXPO_PUBLIC_API_URL=http://localhost:9000
ARG EXPO_PUBLIC_SUPPORT_EMAIL=
ENV EXPO_PUBLIC_API_URL=${EXPO_PUBLIC_API_URL} \
    EXPO_PUBLIC_SUPPORT_EMAIL=${EXPO_PUBLIC_SUPPORT_EMAIL}

RUN npx expo export --platform web --output-dir dist

# ---- Stage 2: static server -------------------------------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.28-alpine

# The image's entrypoint renders /etc/nginx/templates/*.template into /etc/nginx/conf.d at start. Only variables
# whose name matches the filter are substituted, so nginx's own $uri and friends are left alone.
ENV NGINX_ENVSUBST_FILTER=^CSP_ \
    CSP_CONNECT_SRC="http://localhost:9000 ws://localhost:7880"

COPY docker/default.conf.template /etc/nginx/templates/default.conf.template
COPY docker/security-headers.inc.template /etc/nginx/templates/security-headers.inc.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8081

# busybox wget ships with alpine
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8081/healthz || exit 1
