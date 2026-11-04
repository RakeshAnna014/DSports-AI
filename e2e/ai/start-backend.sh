#!/usr/bin/env bash
# Starts the Java backend on :8080 detached from the calling shell, against the real local database (Flyway only migrates forward; nothing is dropped).
# JWT_SECRET is read from ai-service/.env so the AI service and the backend verify the same tokens (never printed).
# Stops only THIS project's backend if one is running; another project's process on the port is left alone and the script fails.
cd "$(dirname "$0")/../.." || exit 1
bash e2e/ai/free-port.sh 8080 || { echo "port 8080 is held by another project's process; not starting" >&2; exit 1; }
sleep 2
export JWT_SECRET="$(grep '^JWT_SECRET=' ai-service/.env | cut -d= -f2-)"
( cd backend && DB_NAME=dsports nohup java -jar bootstrap/target/dsports-bootstrap-1.0.0-SNAPSHOT.jar --spring.profiles.active=local > "${TEMP:-/tmp}/dsports_backend.log" 2>&1 & )
for _ in $(seq 1 90); do curl -s -m2 http://127.0.0.1:8080/actuator/health | grep -q UP && { echo "backend up"; exit 0; }; sleep 2; done
echo "backend did not start (see ${TEMP:-/tmp}/dsports_backend.log)" >&2
exit 1
