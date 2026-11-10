@echo off
cd /d "C:\Users\ranna\Documents\AI\DSports-AI"

echo ===== Starting PostgreSQL =====
docker start dsports-postgres 2>nul
echo PostgreSQL started

echo ===== Starting Backend =====
java -jar "C:\Users\ranna\Documents\AI\DSports-AI\backend\bootstrap\target\dsports-bootstrap-1.0.0-SNAPSHOT.jar" > backend.log 2>&1
echo Backend started

echo ===== Starting Frontend =====
cd "C:\Users\ranna\Documents\AI\DSports-AI\frontend"
pnpm run dev > frontend.log 2>&1
echo Frontend started

echo ===== Starting AI Service =====
cd "C:\Users\ranna\Documents\AI\DSports-AI\ai-service"
.\.venv\scripts\python -m uvicorn app:app --host 0.0.0.0 --port 8000 2>&1
echo AI Service started

echo All services started!