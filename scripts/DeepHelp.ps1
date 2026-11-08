#Requires -Version 5.1
param([ValidateSet('start', 'stop', 'status')][string]$Action = 'status')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'CloudSsh.ps1')
Invoke-CloudControl 'deephelp' $Action
