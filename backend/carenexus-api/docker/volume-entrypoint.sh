#!/bin/sh
# Entrypoint of the `volume-owner` image target (see ../Dockerfile).
# Runs as root only long enough to make STORAGE_LOCAL_DIR (on a root-owned volume) belong to the carenexus user,
# then starts the API as carenexus. Without STORAGE_LOCAL_DIR it just drops privileges.
set -eu

if [ "$(id -u)" = "0" ]; then
    if [ -n "${STORAGE_LOCAL_DIR:-}" ]; then
        mkdir -p "$STORAGE_LOCAL_DIR"
        # Only on first start or after a restore: a recursive chown of a large tree is slow.
        if [ "$(stat -c %U "$STORAGE_LOCAL_DIR")" != "carenexus" ]; then
            chown -R carenexus:carenexus "$STORAGE_LOCAL_DIR"
        fi
        chmod 0700 "$STORAGE_LOCAL_DIR"
    fi
    exec su-exec carenexus java -jar /app/app.jar "$@"
fi

exec java -jar /app/app.jar "$@"
