# PhotoHub: tell the NAS that the Amazon Photos desktop app is running (spec F4).
# Run every hour with Windows Task Scheduler:
#   powershell -NoProfile -ExecutionPolicy Bypass -File C:\photohub\amazon-heartbeat.ps1
# Edit the two values below. The token is the PHOTOHUB_API_TOKEN from the NAS .env.
$Nas   = "http://192.168.1.10:8765"
$Token = "PUT-YOUR-TOKEN-HERE"

$running = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -like "Amazon Photos*" -or $_.ProcessName -like "AmazonPhotos*" }
if (-not $running) {
    Write-Output "Amazon Photos is not running; no heartbeat sent."
    exit 0
}
try {
    Invoke-RestMethod -Method Post -Uri "$Nas/api/amazon/heartbeat" -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 20 | Out-Null
    Write-Output "heartbeat sent"
} catch {
    Write-Output "heartbeat failed: $_"
    exit 1
}
