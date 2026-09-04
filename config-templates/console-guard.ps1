# console-guard.ps1 -- keeps the server console from being able to freeze the server.
#
# WHY THIS EXISTS
#   On 2026-08-31 at 23:08:47 production stopped ticking and stayed stopped for 22h 35m. A text selection
#   was active on its console window; Windows blocks console writes while a selection is held, that blocked
#   Log4j's appender, and the server thread blocked on its next log call. Paper's watchdog fired ten seconds
#   later, but a watchdog dump is itself written to the console, so it blocked partway through and never
#   reached the timeout-time halt that would have killed and restarted the JVM. The safety net was disabled
#   by the very thing it was trying to report.
#
# WHY THE OLD FIX WAS NOT ENOUGH
#   start.bat already ran disable-quickedit.ps1, and it ran, and the selection happened anyway. That script
#   sets the mode once, BEFORE the JVM starts -- and JLine sets its own console mode when Paper initialises
#   the terminal, after which nothing has re-asserted anything. This guard runs alongside the JVM instead and
#   re-asserts continuously, so it wins whatever the terminal does to the mode afterwards and whenever.
#
# WHAT IT DOES
#   Opens CONIN$ directly rather than going through the inherited standard handles -- the guard is launched
#   with `start /b`, whose stdin can be redirected, while CONIN$ always names the attached console's own
#   input buffer. Clears ENABLE_QUICK_EDIT_MODE (0x40) and sets ENABLE_EXTENDED_FLAGS (0x80), which is
#   required for the clear to be honoured at all, then re-checks on a timer for as long as a server is
#   running in this directory. Every action goes to logs/console-guard.log and NEVER to the console: writing
#   to the console is the thing being protected against.
#
#   It cannot cancel a selection somebody has already made by hand. What it can do is make an accidental
#   click-drag stop creating one, which is the path that actually caused the outage.

param(
    [int]$IntervalSeconds = 5,
    [int]$MaxIdleChecks = 24
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path $root 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$log = Join-Path $logDir 'console-guard.log'

function Write-Guard([string]$message) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $message
    Add-Content -Path $log -Value $line -Encoding utf8
}

$signature = @'
[DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
public static extern IntPtr CreateFileW(string name, uint access, uint share, IntPtr security,
                                        uint disposition, uint flags, IntPtr template);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool GetConsoleMode(IntPtr handle, out uint mode);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool SetConsoleMode(IntPtr handle, uint mode);
'@

$api = Add-Type -MemberDefinition $signature -Name ConsoleGuardApi -Namespace Ashfall -PassThru

# Deliberately NOT one guard per directory.
#
# The first version took a pidfile lock so restarts could not pile up guards. That was wrong, and the
# external probe caught it: a guard belongs to a CONSOLE, not to a folder, so a guard still alive on an
# older console made the new one stand down and left the live console at mode 0x9 -- QuickEdit bit clear
# but EXTENDED_FLAGS clear too, which means the console falls back to the registry default and is not
# actually protected. One guard per console, each exiting when its own console goes away (the
# GetConsoleMode failure below), is the correct scope.

# Windows PowerShell 5.1 parses 0xC0000000 as a signed Int32 and hands CreateFileW a negative number,
# which fails to convert and takes the whole guard down before it logs anything. Pinned to uint32.
$GENERIC_READ_WRITE = [uint32]3221225472   # GENERIC_READ | GENERIC_WRITE
$SHARE_READ_WRITE = [uint32]3
$OPEN_EXISTING = [uint32]3
$QUICK_EDIT = [uint32]0x40
$EXTENDED_FLAGS = [uint32]0x80

$handle = $api::CreateFileW('CONIN$', $GENERIC_READ_WRITE, $SHARE_READ_WRITE, [IntPtr]::Zero, $OPEN_EXISTING, [uint32]0, [IntPtr]::Zero)
if ($handle -eq [IntPtr]::Zero -or $handle -eq [IntPtr](-1)) {
    Write-Guard "FAILED to open CONIN$ (error $([System.Runtime.InteropServices.Marshal]::GetLastWin32Error())); guard is not running."
    exit 1
}

$ownerConsole = (Get-CimInstance Win32_Process -Filter "ProcessId=$PID").ParentProcessId
Write-Guard "started; re-asserting every ${IntervalSeconds}s in $root"

$idle = 0
$lastReported = -1
while ($true) {
    $mode = 0
    if ($api::GetConsoleMode($handle, [ref]$mode)) {
        $want = ($mode -band (-bnot $QUICK_EDIT)) -bor $EXTENDED_FLAGS
        if ($mode -ne $want) {
            if ($api::SetConsoleMode($handle, $want)) {
                Write-Guard ("QuickEdit re-disabled: mode 0x{0:X} -> 0x{1:X}" -f $mode, $want)
            } else {
                Write-Guard ("SetConsoleMode failed (error {0}); mode is still 0x{1:X}" -f [System.Runtime.InteropServices.Marshal]::GetLastWin32Error(), $mode)
            }
        } elseif ($mode -ne $lastReported) {
            Write-Guard ("mode is 0x{0:X}; QuickEdit off, extended flags on" -f $mode)
            $lastReported = $mode
        }
    } else {
        Write-Guard "GetConsoleMode failed; the console may be gone. Exiting."
        break
    }

    # THE CHECK BELOW CANNOT TELL THE TWO SERVERS APART.
    #
    # It asks whether ANY java.exe is running paper.jar, and on this host production always is -- so a
    # staging guard whose console has gone never reaches zero and never exits. GetConsoleMode keeps
    # succeeding for an orphan that still holds a handle, so neither exit condition fires and every staging
    # restart leaves one behind, each re-asserting console modes every five seconds forever.
    #
    # The owner console is the thing that actually identifies which server this guard belongs to, and it is
    # the same check freeze-watchdog.ps1 already uses.
    if ($ownerConsole -and -not (Get-CimInstance Win32_Process -Filter "ProcessId=$ownerConsole" -ErrorAction SilentlyContinue)) {
        Write-Guard "the console that launched this guard (pid $ownerConsole) is gone; exiting."
        break
    }

    # Backstop for the case the owner console outlives its server: no paper.jar anywhere means nothing to
    # guard. Tolerates a slow startup before giving up.
    $running = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
                 Where-Object { $_.CommandLine -like '*paper.jar*' })
    if ($running.Count -eq 0) {
        $idle++
        if ($idle -ge $MaxIdleChecks) { Write-Guard "no server process for $($idle * $IntervalSeconds)s; exiting."; break }
    } else {
        $idle = 0
    }

    Start-Sleep -Seconds $IntervalSeconds
}
