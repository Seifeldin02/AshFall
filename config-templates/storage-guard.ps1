# storage-guard.ps1 -- warns long before Prism or the disk becomes a problem again.
#
# WHY THIS EXISTS
#   On 2026-09-02 the host became unusable because plugins/prism/prism.db had reached 36.4 GB across
#   92.7 million rows, on a disk that was down to 22.6 GB free. Nothing anywhere reported it. The server's
#   own TPS stayed at a flawless 20.0 the whole time, because Prism commits off the main thread -- so every
#   in-game signal said the server was healthy while the machine underneath it drowned in write I/O.
#
#   The database was 99.1% one creeper farm: 61.9 million vehicle-ride/vehicle-exit rows on oak boats and
#   29.7 million creeper deaths caused by fall. Filters and a 7-day retention now hold it to roughly 0.2 GB.
#   This script is what notices if that ever stops being true.
#
# WHAT IT DOES
#   Every CheckMinutes it measures the Prism database and free disk space and writes one line to
#   logs/storage-guard.log. When a threshold is crossed it also warns in-game over RCON, so somebody finds
#   out from the server rather than from the laptop going slow. Never writes to the console: console output
#   is exactly what a blocked console can stall on, which is a different incident in this same log.
#
# THRESHOLDS
#   Set against the MEASURED post-filter rate of ~76,500 rows/day (~32 MB/day), which settles a seven-day
#   window at about 0.2 GB. Warning at 2 GB is roughly ten times that -- comfortably clear of normal
#   variation, and still a fortnight of runway before anything hurts.

param(
    [int]$CheckMinutes = 15,
    [double]$PrismWarnGb = 2.0,
    [double]$PrismAlarmGb = 5.0,
    [double]$DiskWarnGb = 40.0,
    [double]$DiskAlarmGb = 20.0
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $root 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$log = Join-Path $logDir 'storage-guard.log'

function Write-Guard([string]$m) {
    Add-Content -Path $log -Encoding utf8 -Value ("[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m)
}

# --- RCON, only used to raise a warning; credentials come from the server's own config ----------------
$props = @{}
foreach ($line in Get-Content (Join-Path $root 'server.properties')) {
    if ($line -match '^\s*([^#=]+)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() }
}
$rconPort = [int]$props['rcon.port']
$rconPass = $props['rcon.password']

function Send-Rcon([string]$command) {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', $rconPort)
        $s = $c.GetStream()
        function Pkt($id, $type, $body) {
            $b = [System.Text.Encoding]::UTF8.GetBytes($body)
            $ms = New-Object System.IO.MemoryStream
            $w = New-Object System.IO.BinaryWriter($ms)
            $w.Write([int]$id); $w.Write([int]$type); $w.Write($b); $w.Write([byte]0); $w.Write([byte]0); $w.Flush()
            $d = $ms.ToArray()
            $f = New-Object System.IO.MemoryStream
            $fw = New-Object System.IO.BinaryWriter($f)
            $fw.Write([int]$d.Length); $fw.Write($d); $fw.Flush()
            $o = $f.ToArray(); $s.Write($o, 0, $o.Length); $s.Flush()
        }
        Pkt 1 3 $rconPass; Start-Sleep -Milliseconds 200
        Pkt 2 2 $command;  Start-Sleep -Milliseconds 200
        $c.Close()
    } catch { Write-Guard "could not deliver the in-game warning: $_" }
}

Write-Guard ("started; checking every {0}m. Prism warn/alarm {1}/{2} GB, free disk warn/alarm {3}/{4} GB" -f `
    $CheckMinutes, $PrismWarnGb, $PrismAlarmGb, $DiskWarnGb, $DiskAlarmGb)

#  THE SAME ORPHAN BUG freeze-watchdog.ps1 ALREADY FIXED FOR ITSELF.
#
#  console-guard exits the moment GetConsoleMode fails, and the freeze watchdog checks whether the console
#  that launched it is still there. This one only ever touches a file and a socket, so nothing ever told it
#  its server had gone -- and every staging restart left another copy behind. Nineteen of them were running
#  at once on 2026-09-04, all polling prism.db and the disk on a laptop shared with production.
$ownerConsole = (Get-CimInstance Win32_Process -Filter "ProcessId=$PID").ParentProcessId

$lastState = ''
while ($true) {
    if ($ownerConsole -and -not (Get-CimInstance Win32_Process -Filter "ProcessId=$ownerConsole" -ErrorAction SilentlyContinue)) {
        Write-Guard "the console that launched this guard (pid $ownerConsole) is gone; exiting."
        break
    }

    $prismDb = Join-Path $root 'plugins\prism\prism.db'
    $prismGb = if (Test-Path $prismDb) { [math]::Round((Get-Item $prismDb).Length / 1GB, 3) } else { 0 }
    $freeGb = [math]::Round((Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='$($root.Substring(0,2))'").FreeSpace / 1GB, 1)

    $state = 'OK'
    $msgs = @()
    if ($prismGb -ge $PrismAlarmGb) { $state = 'ALARM'; $msgs += "Prism database is $prismGb GB (alarm at $PrismAlarmGb)" }
    elseif ($prismGb -ge $PrismWarnGb) { if ($state -eq 'OK') { $state = 'WARN' }; $msgs += "Prism database is $prismGb GB (warn at $PrismWarnGb)" }
    if ($freeGb -le $DiskAlarmGb) { $state = 'ALARM'; $msgs += "only $freeGb GB free on disk (alarm at $DiskAlarmGb)" }
    elseif ($freeGb -le $DiskWarnGb) { if ($state -eq 'OK') { $state = 'WARN' }; $msgs += "only $freeGb GB free on disk (warn at $DiskWarnGb)" }

    Write-Guard ("{0}  prism.db {1} GB  free disk {2} GB{3}" -f $state, $prismGb, $freeGb,
        $(if ($msgs.Count) { ' -- ' + ($msgs -join '; ') } else { '' }))

    # Announce only on a CHANGE of state, so a warning is noticed rather than becoming wallpaper.
    if ($state -ne 'OK' -and $state -ne $lastState) {
        Send-Rcon ("say [storage] {0}: {1}" -f $state, ($msgs -join '; '))
    }
    $lastState = $state

    Start-Sleep -Seconds ($CheckMinutes * 60)
}
