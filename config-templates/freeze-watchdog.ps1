# freeze-watchdog.ps1 -- the layer that makes "the server never stays hung" true no matter the cause.
#
# WHY THIS EXISTS
#   console-guard.ps1 stops the console being ABLE to block, and the log discard policy stops a blocked
#   console being able to block the server thread. Both are preventive, and both are specific to the console.
#   This one is neither: it does not care why the server stopped responding. It watches from outside the JVM
#   entirely, and if the server is alive but not answering for long enough, it kills it and lets
#   restart-server.bat bring it back. A deadlock, a plugin in an infinite loop, a GC death spiral and a
#   blocked console all look identical from here, and all get the same answer.
#
#   The 2026-08-31 outage lasted 22 hours 35 minutes because every mechanism that could have noticed had to
#   write to the thing that was blocked. This one writes to a file and talks over a socket.
#
# HOW IT DECIDES
#   The server is identified by whoever OWNS the RCON listening port -- not by "some java.exe", which would
#   be ambiguous with two servers on one host. A hang is: that process exists, and a real RCON command has
#   not been answered for FailuresBeforeKill consecutive checks. No process listening means the server is
#   stopped, which is not a hang and is none of this script's business.
#
#   RCON is the right probe precisely because answering it requires the MAIN THREAD: the connection is
#   handled on its own thread but the command is scheduled onto the server thread, so a reply proves the
#   thing that actually matters is still ticking. A TCP connect alone would have said "fine" all through the
#   outage.
#
# WHAT IT DOES ON A HANG
#   Launches restart-server.bat first (it waits fifteen seconds before starting anything), then kills the
#   JVM, then closes the old console -- which ends this script too, and the fresh console starts its own.

param(
    [int]$IntervalSeconds = 30,
    [int]$FailuresBeforeKill = 10,
    [int]$RconTimeoutSeconds = 10
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $root 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$log = Join-Path $logDir 'freeze-watchdog.log'

function Write-Watch([string]$message) {
    Add-Content -Path $log -Encoding utf8 -Value ("[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $message)
}

# --- credentials come from the server's own config, never from this file -----------------------------
$props = @{}
foreach ($line in Get-Content (Join-Path $root 'server.properties')) {
    if ($line -match '^\s*([^#=]+)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() }
}
$port = [int]$props['rcon.port']
$password = $props['rcon.password']
if (-not $port -or -not $password) { Write-Watch 'rcon.port or rcon.password missing; watchdog cannot run.'; exit 1 }

function Test-ServerAlive {
    <#  One real RCON command, end to end. Returns $true only if the server ANSWERED, which requires the
        server thread to have run the command -- the whole point of probing this way. #>
    $client = $null
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $connect = $client.BeginConnect('127.0.0.1', $port, $null, $null)
        if (-not $connect.AsyncWaitHandle.WaitOne($RconTimeoutSeconds * 1000)) { return $false }
        $client.EndConnect($connect)
        $client.ReceiveTimeout = $RconTimeoutSeconds * 1000
        $client.SendTimeout = $RconTimeoutSeconds * 1000
        $stream = $client.GetStream()

        function Send-Packet($stream, [int]$id, [int]$type, [string]$body) {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
            $payload = New-Object System.IO.MemoryStream
            $w = New-Object System.IO.BinaryWriter($payload)
            $w.Write([int]$id); $w.Write([int]$type); $w.Write($bytes); $w.Write([byte]0); $w.Write([byte]0)
            $w.Flush()
            $data = $payload.ToArray()
            $frame = New-Object System.IO.MemoryStream
            $fw = New-Object System.IO.BinaryWriter($frame)
            $fw.Write([int]$data.Length); $fw.Write($data); $fw.Flush()
            $out = $frame.ToArray()
            $stream.Write($out, 0, $out.Length); $stream.Flush()
        }
        function Read-Packet($stream) {
            $head = New-Object byte[] 4
            $got = 0
            while ($got -lt 4) {
                $n = $stream.Read($head, $got, 4 - $got)
                if ($n -le 0) { return $null }
                $got += $n
            }
            $len = [BitConverter]::ToInt32($head, 0)
            if ($len -le 0 -or $len -gt 65536) { return $null }
            $body = New-Object byte[] $len
            $got = 0
            while ($got -lt $len) {
                $n = $stream.Read($body, $got, $len - $got)
                if ($n -le 0) { return $null }
                $got += $n
            }
            return [BitConverter]::ToInt32($body, 0)
        }

        Send-Packet $stream 1 3 $password
        if ((Read-Packet $stream) -ne 1) { return $false }
        # `list` is answered by the server thread, so a reply proves it is ticking.
        Send-Packet $stream 2 2 'list'
        return ((Read-Packet $stream) -eq 2)
    } catch {
        return $false
    } finally {
        if ($client) { $client.Close() }
    }
}

<#  One watchdog per SERVER, keyed on its RCON port.

    Unlike console-guard.ps1 -- which is deliberately one per CONSOLE, because a guard can only protect the
    console it is attached to -- redundancy here is actively harmful: two watchdogs reach the kill threshold
    within a second of each other and both call restart-server.bat. Observed exactly that in testing, and
    the second server died on the port the first had taken. This one probes over a socket and does not care
    which console it came from, so an existing live watchdog on the same port is doing the whole job.  #>
<#  Keyed on THIS script's full path, not on the word "freeze-watchdog".

    Staging and production both run one on this host, and a bare name match would have made production's
    watchdog stand down because staging's was already running -- silently leaving the live server unwatched,
    which is the exact opposite of the point. The two paths differ
    (C:\MinecraftServer\... vs C:\MinecraftServer-Staging\...) and neither contains the other.  #>
$mine = $MyInvocation.MyCommand.Path
$others = @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -like "*$mine*" })
if ($others.Count -gt 0) {
    Write-Watch "another watchdog is already running as pid $($others[0].ProcessId); standing down."
    exit 0
}

Write-Watch "started; probing RCON port $port every ${IntervalSeconds}s, killing after $FailuresBeforeKill consecutive failures (~$([math]::Round($IntervalSeconds * $FailuresBeforeKill / 60.0, 1)) minutes)"

$failures = 0
while ($true) {
    Start-Sleep -Seconds $IntervalSeconds

    $owner = $null
    try {
        $listen = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listen) { $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listen.OwningProcess)" -ErrorAction SilentlyContinue }
    } catch { }

    if (-not $owner -or $owner.Name -ne 'java.exe') {
        # Nothing is serving this port: the server is stopped or still starting. Not a hang.
        if ($failures -gt 0) { Write-Watch 'no server listening; counter reset.'; $failures = 0 }
        continue
    }

    if (Test-ServerAlive) {
        if ($failures -gt 0) { Write-Watch "server answered again after $failures failed probe(s)." }
        $failures = 0
        continue
    }

    $failures++
    Write-Watch "RCON probe $failures/$FailuresBeforeKill failed while java pid $($owner.ProcessId) is still running."
    if ($failures -lt $FailuresBeforeKill) { continue }

    Write-Watch "SERVER IS HUNG: no RCON answer for $($failures * $IntervalSeconds)s. Restarting it."

    # Start the replacement FIRST -- restart-server.bat waits fifteen seconds before it launches anything,
    # which is ample for the kill below and means nothing is lost if this script dies with its console.
    $restart = Join-Path $root 'restart-server.bat'
    if (Test-Path $restart) {
        Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'start', "`"Ashfall restart`"", $restart -WorkingDirectory $root
        Write-Watch 'restart-server.bat launched.'
    } else {
        Write-Watch "WARNING: $restart is missing; the server will NOT come back on its own."
    }

    try {
        Stop-Process -Id $owner.ProcessId -Force
        Write-Watch "killed hung java pid $($owner.ProcessId)."
    } catch {
        Write-Watch "could not kill pid $($owner.ProcessId): $_"
    }

    # Close the console this script and the dead server were sharing, so a stale window is not left behind
    # sitting on `pause`. Only ever a cmd.exe, checked by name -- never the terminal host.
    try {
        $self = Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
        $parent = Get-CimInstance Win32_Process -Filter "ProcessId=$($self.ParentProcessId)" -ErrorAction SilentlyContinue
        if ($parent -and $parent.Name -eq 'cmd.exe') {
            Write-Watch "closing the old console (pid $($parent.ProcessId)); this watchdog ends with it."
            Stop-Process -Id $parent.ProcessId -Force
        }
    } catch { }
    exit 0
}
