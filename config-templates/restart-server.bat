@echo off
rem restart-server.bat -- what brings the server back after a hang, from Paper's watchdog or from
rem freeze-watchdog.ps1.
rem
rem spigot.yml settings.restart-script pointed at ./start.sh, a Linux path, on a Windows host. Spigot will
rem not run a script it cannot stat (RestartCommand checks new File(script).isFile()), so the restart
rem silently did nothing -- and on 2026-08-31 that turned what should have been a sixty-second watchdog kill
rem into a 22-hour outage nobody was told about.
rem
rem Spigot invokes this as:  cmd /c start <restart-script>
rem restart-script is set to this file's ABSOLUTE path, and every path below is absolute too, because the
rem watchdog's child inherits the JVM's environment and nothing here may depend on the working directory or
rem on the PATH. NoDefaultCurrentDirectoryInExePath alone is enough to make "cmd /c restart-server.bat"
rem fail with "not recognized" while the file is plainly sitting right there -- found by running it.
rem
rem TWO CALLERS, ONE SERVER. Paper's own watchdog and freeze-watchdog.ps1 can both decide to restart, and
rem during testing they did: two invocations 90ms apart each launched a console, and the second server died
rem on the port the first had taken. A recovery path that can start two servers is worse than the fault it
rem is recovering from, so this script is now single-instance and refuses to launch anything if a server is
rem already listening. Both guards matter -- the lock stops a race, the port check stops a late duplicate.
rem
rem Other Windows traps deliberately avoided here, all found by running rather than by reading:
rem   * %~dp0 ends in a backslash, so "%~dp0" ends the argument with a backslash-quote and ESCAPES the
rem     closing quote. "%~dp0name.bat" is safe because the backslash is followed by text.
rem   * timeout.exe refuses to run when stdin is redirected, which is exactly the state a process launched
rem     by a dying JVM can be in. waitfor reads no stdin and needs no network -- ping was the first choice
rem     and took 79 seconds for a 15 second wait, because the firewall hardening drops loopback ICMP.
rem   * start's first quoted argument is the window TITLE, so a title is given explicitly before the command.

setlocal
set "HERE=%~dp0"
set "LOG=%HERE%logs\restart-server.log"
set "LOCK=%HERE%logs\restart.lock"

if not exist "%HERE%logs" mkdir "%HERE%logs"

rem mkdir is atomic on Windows: exactly one concurrent caller can create the directory, the rest fail.
mkdir "%LOCK%" 2>nul
if errorlevel 1 (
    echo [%DATE% %TIME%] another restart is already in progress; standing down>>"%LOG%"
    exit /b 0
)

echo [%DATE% %TIME%] restart requested>>"%LOG%"

rem Let the dying JVM actually die and release its region files before a new one opens them. A watchdog
rem halt is not a clean shutdown, so this wait is doing real work.
waitfor /t 15 AshfallRestartDelay >nul 2>&1

rem By now the old server should be gone. If something is STILL serving the RCON port, a server is already
rem running -- either it recovered on its own or another caller got there first -- and starting a second one
rem would collide on the port and the world session lock.
for /f "usebackq delims=" %%S in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$p=(Select-String -Path '%HERE%server.properties' -Pattern '^rcon\.port=' | Select-Object -First 1).Line.Split('=')[1].Trim(); if (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { 'UP' } else { 'DOWN' }"`) do set "STATE=%%S"

if "%STATE%"=="UP" (
    echo [%DATE% %TIME%] a server is already listening; not starting a second one>>"%LOG%"
    rmdir "%LOCK%" 2>nul
    exit /b 0
)

rem launch-via-conhost.bat is the visible-console launcher and exists in BOTH server directories, each one
rem cd-ing to its own; it is the right first choice everywhere rather than a production marker.
if exist "%HERE%launch-via-conhost.bat" (
    echo [%DATE% %TIME%] launching via launch-via-conhost.bat>>"%LOG%"
    start "Ashfall Concord" /D "%HERE%." cmd.exe /c "%HERE%launch-via-conhost.bat"
) else if exist "%HERE%start-staging.bat" (
    echo [%DATE% %TIME%] launching via start-staging.bat>>"%LOG%"
    start "Ashfall Concord" /D "%HERE%." cmd.exe /c "%HERE%start-staging.bat"
) else (
    echo [%DATE% %TIME%] launching via start.bat>>"%LOG%"
    start "Ashfall Concord" /D "%HERE%." cmd.exe /c "%HERE%start.bat"
)

rem Held until the new server has had time to bind, so a second caller arriving late sees either the lock or
rem the listening port rather than an unlucky gap between the two.
waitfor /t 60 AshfallRestartSettle >nul 2>&1
rmdir "%LOCK%" 2>nul
endlocal
