# Crée les comptes du broker (A, B, C, DASHBOARD) à partir de mots de passe aléatoires.
#
#   config/mqtt-passwords.properties   mots de passe EN CLAIR, lus par Config.mqttPassword()  (non commité)
#   mosquitto/passwd                   mots de passe hachés, lus par Mosquitto                (non commité)
#
# Usage, depuis la racine du projet :
#     powershell -ExecutionPolicy Bypass -File mosquitto/setup.ps1          # crée ce qui manque
#     powershell -ExecutionPolicy Bypass -File mosquitto/setup.ps1 -Force   # régénère tous les mots de passe
#
# Les mots de passe existants sont conservés (sauf -Force) : mosquitto/passwd est toujours reconstruit
# à partir du fichier .properties, les deux restent donc cohérents.

param([switch]$Force)

$ErrorActionPreference = 'Stop'
$root      = Split-Path -Parent $PSScriptRoot
$propsFile = Join-Path $root 'config/mqtt-passwords.properties'
$passwd    = Join-Path $root 'mosquitto/passwd'
$clients   = @('A', 'B', 'C', 'DASHBOARD')

if (-not (Get-Command mosquitto_passwd -ErrorAction SilentlyContinue)) {
    throw "mosquitto_passwd introuvable dans le PATH (installer Mosquitto ou ajouter C:\Program Files\Mosquitto au PATH)."
}

function New-RandomPassword {
    $bytes = New-Object byte[] 18
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })   # 36 caractères hexa
}

# 1) mots de passe en clair : conservés s'ils existent déjà
$passwords = [ordered]@{}
if ((Test-Path $propsFile) -and -not $Force) {
    foreach ($line in Get-Content $propsFile) {
        if ($line -match '^\s*([A-Z]+)\s*=\s*(.+?)\s*$') { $passwords[$Matches[1]] = $Matches[2] }
    }
}
$created = 0
foreach ($c in $clients) {
    if (-not $passwords.Contains($c)) { $passwords[$c] = New-RandomPassword; $created++ }
}
New-Item -ItemType Directory -Force -Path (Split-Path $propsFile) | Out-Null
$content = @('# Mots de passe MQTT en clair (lus par Config.mqttPassword). NE PAS COMMITER.')
foreach ($c in $clients) { $content += "$c=$($passwords[$c])" }
Set-Content -Path $propsFile -Value $content -Encoding ASCII

# 2) fichier de comptes Mosquitto, reconstruit à partir des mots de passe
$first = $true
foreach ($c in $clients) {
    if ($first) { & mosquitto_passwd -b -c $passwd $c $passwords[$c]; $first = $false }
    else        { & mosquitto_passwd -b    $passwd $c $passwords[$c] }
    if ($LASTEXITCODE -ne 0) { throw "mosquitto_passwd a echoue pour le compte $c" }
}

Write-Host "Comptes prets : $($clients -join ', ') ($created mot(s) de passe genere(s))."
Write-Host "  $propsFile"
Write-Host "  $passwd"
Write-Host "Lancer le broker authentifie depuis la racine :  mosquitto -c mosquitto/mosquitto.conf -v"
