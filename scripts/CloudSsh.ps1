function Get-CloudSshArguments {
    $deepHelp = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'deephelp'
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath "$deepHelp/infra/config/connections.env") {
        if ($line -match '^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$') {
            $settings[$Matches[1]] = $Matches[2].Trim('"').Trim("'")
        }
    }
    @('-T', '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes',
      '-o', "UserKnownHostsFile=$deepHelp/infra/client/known_hosts", '-o', 'KexAlgorithms=curve25519-sha256',
      '-o', 'ConnectTimeout=12', '-i', $settings.DEEPHELP_SSH_KEY_WINDOWS, '-p', $settings.DEEPHELP_SSH_PORT,
      ($settings.DEEPHELP_SSH_USER + '@' + $settings.DEEPHELP_SSH_HOST))
}

function Invoke-CloudControl([string]$Project, [string]$Action) {
    $connection = @(Get-CloudSshArguments)
    & ssh.exe @connection "sudo -n /srv/know-engine/bin/control.sh $Project $Action"
    if ($LASTEXITCODE -ne 0) { throw "$Project cloud $Action failed (exit $LASTEXITCODE)." }
}
