param(
    [Parameter(Mandatory = $true)]
    [string]$Package
)
Start-Process -FilePath "C:\Program Files\BlueStacks_nxt\HD-Player.exe" -ArgumentList @(
    "--instance",
    "Rvc64",
    "--cmd",
    "launchApp",
    "--package",
    $Package
) -WindowStyle Hidden
