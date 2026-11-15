# Ouvre le tableau de bord JavaFX (lecture seule). A lancer dans une AUTRE fenetre que le scenario :
#
#   powershell -ExecutionPolicy Bypass -File scripts\run-dashboard.ps1
#   ... -Keys keys -Tab 1            # onglet de depart : 0 registre, 1 snapshot, 2 journal
#
# Le tableau de bord peut etre ouvert AVANT ou APRES le scenario : les noeuds diffusent leur etat
# chaque seconde (balises NODE_STATUS), il est donc complet moins d'une seconde apres sa connexion.
# Il doit utiliser le meme dossier de cles que le scenario (par defaut : keys).
#
# Remarque : messages en ASCII, PowerShell 5.1 lit ce fichier en ANSI.

param(
    [string]$Keys = "keys",
    [int]$Tab = 0,
    [string]$Broker = "",
    [string]$Options = ""          # options supplementaires, ex. "--capture=dash.png --capture-after=5000"
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

$mvn = Find-Maven
Write-Host "Maven : $mvn"

$jfxArgs = "--keys=$Keys --tab=$Tab"
if ($Broker -ne "") { $jfxArgs += " --broker=$Broker" }
if ($Options -ne "") { $jfxArgs += " $Options" }

# hors ligne d'abord (rapide) ; en ligne seulement si le plugin JavaFX n'est pas encore telecharge (environ 10 Mo)
& $mvn -o -q javafx:run "-Djavafx.args=$jfxArgs"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Nouvel essai en ligne (premier lancement : telechargement de JavaFX)..."
    & $mvn -q javafx:run "-Djavafx.args=$jfxArgs"
}
