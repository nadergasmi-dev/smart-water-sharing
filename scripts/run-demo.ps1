# Lance le scenario de demonstration : les 3 agriculteurs (A, B, C) dans une seule fenetre.
#
#   powershell -ExecutionPolicy Bypass -File scripts\run-demo.ps1             # scenario normal
#   powershell -ExecutionPolicy Bypass -File scripts\run-demo.ps1 -Triche     # + C diffuse 2 faux blocs
#   ... -DryRun                                                               # verifie tout sans lancer
#
# Prerequis : un broker Mosquitto demarre sur localhost:1883 (le service Windows "mosquitto" convient).
# Le scenario s'arrete tout seul quand les quotas sont epuises, ou sur Entree.
# Ouvrir le tableau de bord dans une AUTRE fenetre : scripts\run-dashboard.ps1 (peu importe l'ordre).
#
# Remarque : messages en ASCII, PowerShell 5.1 lit ce fichier en ANSI.

param(
    [switch]$Triche,
    [switch]$DryRun,
    [string]$Keys = "keys",
    [int]$Period = 800,
    [int]$SnapshotEvery = 4000
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)      # racine du projet

function Find-Maven {
    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        "$env:ProgramFiles\NetBeans-17\netbeans\java\maven\bin\mvn.cmd",
        "$env:ProgramFiles\JetBrains\IntelliJ IDEA Community Edition 2025.1.3\plugins\maven\lib\maven3\bin\mvn.cmd"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $nb = Get-ChildItem "$env:ProgramFiles" -Directory -Filter "NetBeans*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($nb) {
        $m = Join-Path $nb.FullName "netbeans\java\maven\bin\mvn.cmd"
        if (Test-Path $m) { return $m }
    }
    throw "Maven (mvn) introuvable : l'ajouter au PATH ou installer NetBeans."
}

function Find-Jar([string]$prefix) {
    $jar = Get-ChildItem "$env:USERPROFILE\.m2\repository" -Recurse -Filter "$prefix*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $jar) { throw "Bibliotheque $prefix introuvable dans ~/.m2 : lancer une fois 'mvn compile' avec Internet." }
    return $jar.FullName
}

$mvn = Find-Maven
Write-Host "Maven : $mvn"

# 1) compilation (hors ligne d'abord, puis en ligne si une dependance manque)
& $mvn -o -q compile
if ($LASTEXITCODE -ne 0) { & $mvn -q compile; if ($LASTEXITCODE -ne 0) { throw "Echec de la compilation." } }

$cp = "target\classes;" + (Find-Jar "org.eclipse.paho.client.mqttv3") + ";" + (Find-Jar "gson-")

# 2) cles de signature : generees une seule fois (jamais ecrasees)
if (-not (Test-Path (Join-Path $Keys "A.key"))) {
    Write-Host "Generation des cles dans $Keys ..."
    & java -cp $cp com.smartwater.security.KeyGenerator
}

# 3) le broker doit repondre
$ok = $false
try { $c = New-Object System.Net.Sockets.TcpClient; $c.Connect("localhost", 1883); $c.Close(); $ok = $true } catch { }
if (-not $ok) { Write-Warning "Aucun broker sur localhost:1883 : demarrer Mosquitto (service 'mosquitto') avant le scenario." }

$demoArgs = @("--keys=$Keys", "--period=$Period", "--snapshot-every=$SnapshotEvery")
if ($Triche) { $demoArgs += "--triche" }

Write-Host ("java -cp ... com.smartwater.node.ScenarioDemo " + ($demoArgs -join " "))
if ($DryRun) { Write-Host "(DryRun : rien lance)"; exit 0 }

Write-Host "Scenario lance. Ouvrir le tableau de bord dans une autre fenetre : scripts\run-dashboard.ps1"
& java -cp $cp com.smartwater.node.ScenarioDemo @demoArgs
