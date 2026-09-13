#!/bin/sh
set -eu

if [ -f /app/config/xhrec.json ]; then
    ln -sf /app/config/xhrec.json /app/xhrec.json
fi

# All other options (output dir, tmp dir, port, TLS, list.conf/users.txt
# paths, postprocessor path) are set via XHREC_* environment variables in
# docker-compose.yml instead of being hardcoded here - see that file for
# the full list and defaults. The app falls back to its own built-in
# defaults for anything not set.
exec java -jar xhrec.jar "$@"
