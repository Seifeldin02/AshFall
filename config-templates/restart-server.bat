@echo off
rem restart-server.bat -- what Paper's watchdog runs when it halts a hung JVM.
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
rem Identical on staging and production on purpose so deploy/check_deploy.py keeps the two in sync; it picks
rem the local launcher by which one exists next to it.
rem
rem Other Windows traps deliberately avoided here, all found by running rather than by reading:
rem   * %~dp0 ends in a backslash, so "%~dp0" ends the argument with \" and ESCAPES the closing quote.
rem     "%~dp0name.bat" is safe because the backslash is followed by text; "%~dp0" alone is not.
rem   * timeout.exe refuses to run when stdin is redirected, which is exactly the state a process launched
rem     by a dying JVM can be in. waitfor reads no stdin and needs no network -- ping was the first choice
rem     and took 79 seconds for a 15 second wait, because the firewall hardening drops loopback ICMP and
rem     every echo sat out its full timeout.
rem   * start's first quoted argument is the window TITLE, so a title is given explicitly before the command.

set "HERE=%~dp0"
echo [%DATE% %TIME%] restart requested>>"%HERE%logs\restart-server.log"

rem Let the halted JVM actually die and release its region files before a new one opens them. A watchdog
rem halt is not a clean shutdown, so this wait is doing real work.
waitfor /t 15 AshfallRestartDelay >nul 2>&1

rem launch-via-conhost.bat is the visible-console launcher and exists in BOTH server directories, each one
rem cd-ing to its own; it is the right first choice everywhere rather than a production marker.
if exist "%HERE%launch-via-conhost.bat" (
    echo [%DATE% %TIME%] launching via launch-via-conhost.bat>>"%HERE%logs\restart-server.log"
    start "Ashfall Concord" /D "%HERE%." cmd.exe /c "%HERE%launch-via-conhost.bat"
) else if exist "%HERE%start-staging.bat" (
    echo [%DATE% %TIME%] launching via start-staging.bat>>"%HERE%logs\restart-server.log"
    start "Ashfall Concord" /D "%HERE%." cmd.exe /c "%HERE%start-staging.bat"
) else (
    echo [%DATE% %TIME%] launching via start.bat>>"%HERE%logs\restart-server.log"
    start "Ashfall Concord" /D "%HERE%." cmd.exe /c "%HERE%start.bat"
)
