#!/bin/sh
set -eu

# The "-post" CLI flag has a known parsing issue in some builds, so
# instead we symlink the mounted postprocessor.json to the default
# relative location the app looks for (./postprocessor.json under
# WORKDIR /app). Same idea for xhrec.json so decrypt keys persist
# across container recreation instead of resetting to defaults.
if [ -f /app/config/postprocessor.json ]; then
    ln -sf /app/config/postprocessor.json /app/postprocessor.json
fi
if [ -f /app/config/xhrec.json ]; then
    ln -sf /app/config/xhrec.json /app/xhrec.json
fi

set -- -f /app/config/list.conf -o /app/recordings -p 8090

if [ -f /app/config/users.txt ]; then
    set -- "$@" -u /app/config/users.txt
fi

exec java -jar xhrec.jar "$@"