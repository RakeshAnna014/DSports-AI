# Starts the AI service for evaluation/E2E (port 8090) with a high rate limit, the embedding region and the price table used for cost estimates.
param([string]$Log = "$env:TEMP\ai_service.log")
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..\ai-service")
Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
Start-Sleep 2
$env:AI_RATE_LIMIT_PER_MIN = "1000"
$env:AI_EMBEDDING_LOCATION = "asia-south1"
# Operator-supplied estimate inputs: paid-tier prices published at ai.google.dev (checked 2026-10-07), USD per 1M tokens.
$env:AI_PRICE_TABLE_JSON = '{"gemini-2.5-flash":{"input_per_1m":0.30,"output_per_1m":2.50},"gemini-2.5-flash-lite":{"input_per_1m":0.10,"output_per_1m":0.40}}'
Start-Process -FilePath "uv" -ArgumentList "run", "uvicorn", "app.main:create_app", "--factory", "--port", "8090", "--no-access-log" -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $Log -RedirectStandardError "$Log.err" | Out-Null
for ($i = 0; $i -lt 45; $i++) {
    Start-Sleep 2
    try { if ((Invoke-RestMethod http://localhost:8090/health -TimeoutSec 2).status -eq "UP") { "ai-service up"; exit 0 } } catch { }
}
Write-Error "ai-service did not start; see $Log.err"
