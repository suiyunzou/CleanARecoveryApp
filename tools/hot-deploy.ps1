#Requires -Version 5.1
<#
.SYNOPSIS
  Watch app sources and auto installDebug + restart activity on the emulator.

.DESCRIPTION
  Native Android (Java/XML) has no Flutter-style hot reload. This script is the
  closest Cursor-friendly loop: on file save -> incremental Gradle install ->
  restart the current (or default) activity.

.PARAMETER DebounceMs
  Wait this long after the last file change before deploying.

.PARAMETER Activity
  Component to restart. Default: MainActivity.
#>
param(
    [int]$DebounceMs = 800,
    [string]$PackageId = "com.example.cleanrecovery.musicapp",
    [string]$Activity = "com.example.cleanrecovery.ui.activity.MainActivity",
    [string[]]$WatchPaths = @(
        "app\src\main\res",
        "app\src\main\java",
        "app\src\main\AndroidManifest.xml"
    )
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

# Shared across FileSystemWatcher event runspaces.
$State = [hashtable]::Synchronized(@{
    Pending    = $false
    LastChange = Get-Date
})

function Get-EmulatorSerial {
    $lines = adb devices | Where-Object { $_ -match "^emulator-\d+\s+device$" }
    if (-not $lines) { return $null }
    return ($lines | Select-Object -First 1) -replace "\s+device$", ""
}

function Ensure-Emulator {
    $serial = Get-EmulatorSerial
    if ($serial) { return $serial }
    Write-Host "[hot-deploy] No emulator online. Start CleanDev_API35 first." -ForegroundColor Yellow
    Write-Host "  D:\ProgramFile\AndroidEmulator\start-emulator.bat"
    exit 1
}

function Deploy-Now {
    param([string]$Serial, [string]$Reason)

    Write-Host ""
    Write-Host "[hot-deploy] $Reason -> installDebug + restart" -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    & .\gradlew.bat :app:installDebug
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[hot-deploy] BUILD/INSTALL FAILED" -ForegroundColor Red
        return
    }

    adb -s $Serial shell am force-stop $PackageId | Out-Null
    adb -s $Serial shell am start -n "$PackageId/$Activity" | Out-Null
    $sw.Stop()
    Write-Host ("[hot-deploy] Ready in {0:n1}s  ({1})" -f $sw.Elapsed.TotalSeconds, $Activity) -ForegroundColor Green
}

$serial = Ensure-Emulator
Write-Host "[hot-deploy] Watching under $Root" -ForegroundColor Green
Write-Host "[hot-deploy] Device: $serial"
Write-Host "[hot-deploy] Restart: $PackageId/$Activity"
Write-Host "[hot-deploy] Debounce: ${DebounceMs}ms  |  Ctrl+C to stop"
Write-Host ""

Deploy-Now -Serial $serial -Reason "initial"

$watchers = New-Object System.Collections.Generic.List[System.IO.FileSystemWatcher]
$subscribers = New-Object System.Collections.Generic.List[System.Management.Automation.PSEventJob]

function Register-TreeWatcher {
    param([string]$Path)

    $full = Join-Path $Root $Path
    if (-not (Test-Path $full)) {
        Write-Host "[hot-deploy] Skip missing path: $Path" -ForegroundColor DarkYellow
        return
    }

    if (Test-Path $full -PathType Leaf) {
        $dir = Split-Path $full -Parent
        $filter = Split-Path $full -Leaf
        $includeSub = $false
    } else {
        $dir = $full
        $filter = "*.*"
        $includeSub = $true
    }

    $fsw = New-Object System.IO.FileSystemWatcher $dir, $filter
    $fsw.IncludeSubdirectories = $includeSub
    $fsw.NotifyFilter = [IO.NotifyFilters]::FileName -bor `
        [IO.NotifyFilters]::LastWrite -bor `
        [IO.NotifyFilters]::DirectoryName
    $fsw.EnableRaisingEvents = $true

    $action = {
        $name = $Event.SourceEventArgs.Name
        if ($name -match '(\\build\\|\\.gradle\\|~|\.tmp$|\.swp$|\.DS_Store$)') { return }
        $s = $Event.MessageData
        $s.Pending = $true
        $s.LastChange = Get-Date
        Write-Host "[hot-deploy] change: $name" -ForegroundColor DarkGray
    }

    foreach ($evt in @("Changed", "Created", "Deleted", "Renamed")) {
        $subscribers.Add((Register-ObjectEvent -InputObject $fsw -EventName $evt -Action $action -MessageData $State)) | Out-Null
    }
    $watchers.Add($fsw) | Out-Null
}

foreach ($p in $WatchPaths) { Register-TreeWatcher -Path $p }

try {
    while ($true) {
        Start-Sleep -Milliseconds 200
        if (-not $State.Pending) { continue }
        $idle = ((Get-Date) - [datetime]$State.LastChange).TotalMilliseconds
        if ($idle -lt $DebounceMs) { continue }

        $State.Pending = $false
        $serial = Get-EmulatorSerial
        if (-not $serial) {
            Write-Host "[hot-deploy] Emulator disconnected; waiting..." -ForegroundColor Yellow
            $State.Pending = $true
            continue
        }
        Deploy-Now -Serial $serial -Reason "file change"
    }
} finally {
    foreach ($sub in $subscribers) {
        Unregister-Event -SourceIdentifier $sub.Name -ErrorAction SilentlyContinue
        Remove-Job $sub -Force -ErrorAction SilentlyContinue
    }
    foreach ($w in $watchers) {
        $w.EnableRaisingEvents = $false
        $w.Dispose()
    }
}
