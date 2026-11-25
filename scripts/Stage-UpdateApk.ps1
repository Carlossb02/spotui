param(
    [Parameter(Mandatory)] [string] $Apk,
    [Parameter(Mandatory)] [string] $PreviousApk,
    [Parameter(Mandatory)] [string] $Destination
)

$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (!$sdk) { throw 'Indica la ruta del SDK de Android en ANDROID_HOME.' }
$buildTools = Get-ChildItem -Directory (Join-Path $sdk 'build-tools') |
    Where-Object { (Test-Path (Join-Path $_.FullName 'aapt.exe')) -and
        (Test-Path (Join-Path $_.FullName 'apksigner.bat')) } |
    Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
    Select-Object -First 1
if (!$buildTools) { throw 'No se encontraron aapt y apksigner en el SDK.' }

function Get-ApkIdentity([string] $Path) {
    $badging = & (Join-Path $buildTools.FullName 'aapt.exe') dump badging $Path
    if ($LASTEXITCODE -ne 0 -or $badging[0] -notmatch "^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'") {
        throw "No se pudo leer el manifiesto de $Path"
    }
    $identity = @{ Package = $Matches[1]; Code = [long]$Matches[2]; Version = $Matches[3] }
    $signature = & (Join-Path $buildTools.FullName 'apksigner.bat') verify --print-certs $Path
    if ($LASTEXITCODE -ne 0) { throw "No se pudo verificar la firma de $Path" }
    $identity.Signers = @($signature | ForEach-Object {
        if ($_ -match '^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]+)$') { $Matches[1] }
    } | Sort-Object)
    if (!$identity.Signers.Count) { throw "No se encontró el certificado de $Path" }
    return $identity
}

$candidate = (Resolve-Path -LiteralPath $Apk).Path
$current = Get-ApkIdentity $candidate
$previous = Get-ApkIdentity (Resolve-Path -LiteralPath $PreviousApk).Path
if ($current.Package -cne $previous.Package -or
    ($current.Signers -join ',') -ne ($previous.Signers -join ',') -or
    $current.Code -le $previous.Code) {
    throw 'El APK debe conservar el paquete y la firma del publicado y aumentar el versionCode.'
}
if ($current.Version -notmatch '^[0-9]+(?:\.[0-9]+)+$') { throw 'El nombre de versión del APK no es válido.' }

$target = Join-Path $Destination ("Spotidroid_v" + $current.Version + '.apk')
if (Test-Path -LiteralPath $target) { throw "El archivo de destino ya existe: $target" }
Copy-Item -LiteralPath $candidate -Destination $target
Get-Item -LiteralPath $target
