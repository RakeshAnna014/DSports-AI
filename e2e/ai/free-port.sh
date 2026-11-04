#!/usr/bin/env bash
# Frees a TCP port ONLY when the process holding it belongs to this project (its command line mentions DSports-AI or the dsports-bootstrap jar).
# A process of any other project is never touched: the script reports it and exits 1, so the caller can mark the step BLOCKED instead of killing someone else's work.
# Usage: bash e2e/ai/free-port.sh <port>      exit 0 = port is free now, 1 = held by a foreign process
port="$1"
powershell -NoProfile -c "
\$foreign = \$false
foreach (\$c in Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
  \$id = \$c.OwningProcess
  \$cmd = (Get-CimInstance Win32_Process -Filter \"ProcessId=\$id\" -ErrorAction SilentlyContinue).CommandLine
  if (\$cmd -match 'DSports-AI|dsports-bootstrap') { Stop-Process -Id \$id -Force -ErrorAction SilentlyContinue }
  else { \$foreign = \$true; Write-Output \"port $port is held by another project's process (pid \$id); not touching it\" }
}
if (\$foreign) { exit 1 } else { exit 0 }
"
