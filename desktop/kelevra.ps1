# Kelevra на компьютере.
#
# Один раз: .\kelevra.ps1 install <код доступа>   (от администратора — нужен драйвер туннеля)
# Дальше:   служба поднимается сама при загрузке, конфиг обновляется каждый час.
#
# Без прав администратора можно запустить в режиме прокси: .\kelevra.ps1 proxy <код>

param(
    [Parameter(Position = 0)][ValidateSet('install', 'uninstall', 'update', 'proxy', 'status')]
    [string]$Command = 'status',
    [Parameter(Position = 1)][string]$Code
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Core = Join-Path $Root 'kelevra-core.exe'
$Config = Join-Path $Root 'config.json'
$CodeFile = Join-Path $Root 'code.txt'
$ServiceName = 'Kelevra'
$Host_ = 'subkv.chickenkiller.com'

function Get-Code {
    if ($Code) { return $Code }
    if (Test-Path $CodeFile) { return (Get-Content $CodeFile -Raw).Trim() }
    throw "Не указан код доступа. Запустите: .\kelevra.ps1 $Command <код>"
}

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Update-Config {
    param([string]$AccessCode, [switch]$ProxyMode)

    $url = "https://$Host_/s/$AccessCode"
    Write-Host "Забираю конфиг…"
    $raw = Invoke-WebRequest -Uri $url -UseBasicParsing -Headers @{
        'User-Agent' = 'Kelevra/desktop (sing-box-xhttp)'
    }
    $cfg = $raw.Content | ConvertFrom-Json

    # ключ Android-клиента на компьютере не нужен
    if ($cfg.route.PSObject.Properties.Name -contains 'override_android_vpn') {
        $cfg.route.PSObject.Properties.Remove('override_android_vpn')
    }

    if ($ProxyMode) {
        # Прокси на порту, БЕЗ записи в настройки Windows: если процесс умрёт не по-людски,
        # система не останется с прокси в никуда (наступал на это).
        # Системный прокси включается отдельной командой и снимается штатно.
        $cfg.inbounds = @(@{
                type             = 'mixed'
                tag              = 'mixed-in'
                listen           = '127.0.0.1'
                listen_port      = 2080
                set_system_proxy = $false
            })
    }
    else {
        $cfg.inbounds = @(@{
                type         = 'tun'
                tag          = 'tun-in'
                address      = @('172.19.0.1/30')
                auto_route   = $true
                strict_route = $true
                stack        = 'system'
                mtu          = 9000
            })
    }

    # без метки кодировки: ядро её не переваривает
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Config, ($cfg | ConvertTo-Json -Depth 40), $utf8)
    [System.IO.File]::WriteAllText($CodeFile, $AccessCode, $utf8)

    & $Core check -c $Config
    if ($LASTEXITCODE -ne 0) { throw "Конфиг не прошёл проверку" }
    Write-Host "Конфиг обновлён и проверен."
}

switch ($Command) {
    'install' {
        if (-not (Test-Admin)) { throw "Нужны права администратора: туннель без них не поднимется." }
        Update-Config -AccessCode (Get-Code)

        if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
            sc.exe stop $ServiceName | Out-Null
            sc.exe delete $ServiceName | Out-Null
            Start-Sleep -Seconds 2
        }
        sc.exe create $ServiceName binPath= "`"$Core`" run -c `"$Config`"" start= auto DisplayName= "Kelevra" | Out-Null
        sc.exe description $ServiceName "Своя сеть: туннель, правила, фильтрация рекламы" | Out-Null
        sc.exe start $ServiceName | Out-Null

        # обновление конфига раз в час
        $task = "KelevraConfigUpdate"
        schtasks /Delete /TN $task /F 2>$null | Out-Null
        schtasks /Create /TN $task /SC HOURLY /RU SYSTEM /TR "powershell -NoProfile -ExecutionPolicy Bypass -File `"$($MyInvocation.MyCommand.Path)`" update" | Out-Null

        Write-Host "Готово. Служба запущена и будет стартовать при включении компьютера."
    }
    'uninstall' {
        if (-not (Test-Admin)) { throw "Нужны права администратора." }
        sc.exe stop $ServiceName 2>$null | Out-Null
        sc.exe delete $ServiceName 2>$null | Out-Null
        schtasks /Delete /TN "KelevraConfigUpdate" /F 2>$null | Out-Null
        Write-Host "Удалено."
    }
    'update' {
        Update-Config -AccessCode (Get-Code)
        if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
            Restart-Service -Name $ServiceName
            Write-Host "Служба перезапущена с новым конфигом."
        }
    }
    'proxy' {
        Update-Config -AccessCode (Get-Code) -ProxyMode
        Write-Host "Запускаю в режиме системного прокси (Ctrl+C для остановки)…"
        & $Core run -c $Config
    }
    'status' {
        $svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
        if ($svc) { Write-Host "Служба: $($svc.Status)" } else { Write-Host "Служба не установлена" }
        if (Test-Path $Config) {
            $cfg = Get-Content $Config -Raw | ConvertFrom-Json
            $outs = @($cfg.outbounds | Where-Object { $_.type -eq 'vless' } | ForEach-Object { $_.tag })
            Write-Host "Выходов в конфиге: $($outs.Count) [$($outs -join ', ')]"
        }
        else { Write-Host "Конфига нет — запустите install или proxy" }
    }
}
