@echo off
cd /d "C:\Users\ranna\Documents\AI\DSports-AI\backend"
java -jar "C:\Users\ranna\Documents\AI\DSports-AI\backend\bootstrap\target\dsports-bootstrap-1.0.0-SNAPSHOT.jar" > backend.log 2>&1
echo Backend started on port 8080
timeout /t 1 /nobreak > nul