#Requires -Version 5.1
param(
    [ValidateSet('start', 'stop', 'status')][string]$Action = 'status',
    [switch]$MiddlewareOnly
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'CloudSsh.ps1')

$root = Split-Path $PSScriptRoot -Parent
$local = Join-Path $root '.local'
$jdk = Join-Path $local 'tools/jdk-21/jdk-21.0.13+9'
$java = Join-Path $jdk 'bin/java.exe'
$ssh = (Get-Command ssh.exe).Source
$ports = [ordered]@{ '23306' = 13306; '26379' = 6379; '19200' = 19200; '19090' = 19090; '18000' = 18000 }
$cloudMarker = '-Dknow.engine.launch=cloud'
$appUrl = 'http://127.0.0.1:8009'

function Get-OwnedProcess([string]$Name, [string]$Executable, [string]$Marker) {
    $path = Join-Path $local "run/$Name.pid"
    if (!(Test-Path -LiteralPath $path)) { return }
    $recordedPid = [int](Get-Content -LiteralPath $path -Raw)
    $candidate = Get-CimInstance Win32_Process -Filter "ProcessId=$recordedPid"
    if ($candidate -and $candidate.ExecutablePath -eq $Executable -and $candidate.CommandLine.Contains($Marker)) {
        $candidate
    }
}

function Get-Listener([int]$Port) {
    Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
}

function Stop-App {
    $app = Get-OwnedProcess 'application' $java 'know.engine.KnowEngineApplication'
    if (!$app) {
        if (Get-Listener 8009) { throw 'Port 8009 is occupied by an unmanaged process.' }
        return
    }
    if ($app.CommandLine.Contains($cloudMarker) -and (Get-Listener 8009).OwningProcess -eq $app.ProcessId) {
        Invoke-RestMethod "$appUrl/actuator/shutdown" -Method Post -ContentType 'application/json' -Body '{}' -TimeoutSec 15 | Out-Null
        for ($attempt = 0; $attempt -lt 70; $attempt++) {
            if ((Get-Listener 8009).OwningProcess -ne $app.ProcessId) { break }
            Start-Sleep -Milliseconds 500
        }
        if ((Get-Listener 8009).OwningProcess -eq $app.ProcessId) { throw 'Java HTTP listener did not shut down.' }
        Wait-Process -Id $app.ProcessId -Timeout 5 -ErrorAction SilentlyContinue
    }
    # Some client threads outlive the closed Spring context. End only the recorded JVM.
    $remaining = Get-OwnedProcess 'application' $java 'know.engine.KnowEngineApplication'
    if ($remaining -and $remaining.ProcessId -eq $app.ProcessId -and $remaining.CreationDate -eq $app.CreationDate) {
        Stop-Process -Id $app.ProcessId
        Wait-Process -Id $app.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
    }
    if (Get-Process -Id $app.ProcessId -ErrorAction SilentlyContinue) { throw 'Java application did not exit.' }
}

function Stop-Tunnel {
    $tunnel = Get-OwnedProcess 'cloud-tunnel' $ssh '127.0.0.1:23306:127.0.0.1:13306'
    if ($tunnel) { Stop-Process -Id $tunnel.ProcessId }
}

function Start-Tunnel {
    $tunnel = Get-OwnedProcess 'cloud-tunnel' $ssh '127.0.0.1:23306:127.0.0.1:13306'
    if ($tunnel) {
        foreach ($port in $ports.Keys) {
            if ((Get-Listener $port).OwningProcess -ne $tunnel.ProcessId) { throw "Tunnel port $port is missing." }
        }
        return
    }
    $arguments = @('-N', '-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=20', '-o', 'ServerAliveCountMax=3')
    foreach ($port in $ports.Keys) {
        if (Get-Listener $port) { throw "Tunnel port $port is already occupied." }
        $arguments += @('-L', "127.0.0.1:${port}:127.0.0.1:$($ports[$port])")
    }
    $arguments += @(Get-CloudSshArguments)
    $quoted = $arguments | ForEach-Object { '"' + $_ + '"' }
    $process = Start-Process -FilePath $ssh -ArgumentList $quoted -WindowStyle Hidden -PassThru `
        -RedirectStandardError "$local/logs/cloud-tunnel.err.log"
    Set-Content -LiteralPath "$local/run/cloud-tunnel.pid" -Value $process.Id
    for ($attempt = 0; $attempt -lt 24; $attempt++) {
        if ($process.HasExited) { break }
        $ready = $true
        foreach ($port in $ports.Keys) { $ready = $ready -and ((Get-Listener $port).OwningProcess -eq $process.Id) }
        if ($ready) { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'SSH tunnel did not become ready. See .local/logs/cloud-tunnel.err.log.'
}

function Start-App {
    $app = Get-OwnedProcess 'application' $java 'know.engine.KnowEngineApplication'
    if ($app -and $app.CommandLine.Contains($cloudMarker)) { return }
    Stop-App
    $previousJava = $env:JAVA_HOME
    $env:JAVA_HOME = $jdk
    $classpathFile = Join-Path $local 'java-classpath.txt'
    Push-Location $root
    try {
        $target = Get-Item -LiteralPath (Join-Path $root 'target') -ErrorAction SilentlyContinue
        if ($target -and ($target.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Maven target is a reparse point.' }
        & mvn -B -o -q '-Dmaven.repo.local=D:/maven-repository' clean compile `
            org.apache.maven.plugins:maven-dependency-plugin:2.8:build-classpath "-Dmdep.outputFile=$classpathFile" '-Dmdep.regenerateFile=true'
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
    } finally {
        Pop-Location
        $env:JAVA_HOME = $previousJava
    }
    $classpath = 'target/classes;' + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $appArguments = @($cloudMarker, '-cp', ('"' + $classpath + '"'), 'know.engine.KnowEngineApplication',
        '--server.address=127.0.0.1', '--management.endpoint.shutdown.access=unrestricted',
        '--management.endpoints.web.exposure.include=health,shutdown')
    $process = Start-Process -FilePath $java -ArgumentList $appArguments -WorkingDirectory $root -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "$local/logs/application.out.log" -RedirectStandardError "$local/logs/application.err.log"
    Set-Content -LiteralPath "$local/run/application.pid" -Value $process.Id
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($process.HasExited) { break }
        if ((Get-Listener 8009).OwningProcess -eq $process.Id) { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'Java application did not start. See .local/logs/application.out.log and application.err.log.'
}

New-Item -ItemType Directory -Force "$local/run", "$local/logs" | Out-Null
switch ($Action) {
    'start' {
        if (!(Test-Path -LiteralPath "$local/cloud.properties")) { throw 'Missing .local/cloud.properties.' }
        Invoke-CloudControl 'know-engine' 'start'
        Start-Tunnel
        if (!$MiddlewareOnly) {
            Start-App
            Write-Host "Know Engine: $appUrl/document.html"
        }
    }
    'stop' {
        Stop-App
        Stop-Tunnel
        Invoke-CloudControl 'know-engine' 'stop'
    }
    'status' {
        Invoke-CloudControl 'know-engine' 'status'
        Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -in @($ports.Keys) -or $_.LocalPort -eq 8009 } |
            Select-Object LocalAddress, LocalPort, OwningProcess
    }
}
