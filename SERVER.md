# Minecraft server

These scripts start a Minecraft server using Leverage accounts for authentication.
Start the [Leverage service](README.md#leverage-service) first, then save one of the
scripts below next to your Minecraft server JAR.

Edit the values at the top before running it:

- `AUTH_URL`: your Leverage service URL, including `/api/yggdrasil/`.
- `SERVER_JAR`: the Minecraft server JAR's filename.
- `JAVA`: the Java executable required by your Minecraft version.
- `MIN_RAM` and `MAX_RAM`: the memory limits for the game server.

The example URL connects to a Leverage service on the same machine. For remote
deployments, use your public HTTPS endpoint.

Both scripts use the same pinned authlib-injector version as Leverage. They verify
SHA-256 on every run and download from the author's site if the agent is missing
or invalid. Downloads are verified before replacing the existing file.

They create `server.properties` if missing. Existing settings are preserved;
`online-mode=true` is required. For Minecraft 1.19+, also keep
`enforce-secure-profile=true`, as described in the
[authlib-injector documentation](https://yushijinhun.github.io/authlib-injector/en/using-authlib-injector-on-a-minecraft-server.html).
Minecraft's EULA must be accepted separately before the game server can run.

## Bash: `start-server.sh`

Run with `bash start-server.sh`. Requires `curl` and either `sha256sum` or `shasum`.

```bash
#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

AUTH_URL='http://127.0.0.1:8080/api/yggdrasil/'
SERVER_JAR='server.jar'
JAVA='java'
MIN_RAM='1G'
MAX_RAM='2G'

AGENT='authlib-injector-1.2.8.jar'
DOWNLOAD="https://authlib-injector.yushi.moe/artifact/56/$AGENT"
SHA256='9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10'

die() { printf '%s\n' "$*" >&2; exit 1; }
[[ -f "$SERVER_JAR" ]] || die "Server JAR not found: $SERVER_JAR"
command -v "$JAVA" >/dev/null || die 'Java was not found. Set JAVA above.'
if command -v sha256sum >/dev/null; then
    HASH=(sha256sum)
elif command -v shasum >/dev/null; then
    HASH=(shasum -a 256)
else
    die 'Install sha256sum or shasum.'
fi
valid_agent() {
    [[ -f "$1" ]] || return 1
    local digest
    digest=$("${HASH[@]}" "$1") || return 1
    [[ "${digest%% *}" == "$SHA256" ]]
}

if [[ ! -f server.properties ]]; then
    printf '%s\n' 'online-mode=true' 'enforce-secure-profile=true' > server.properties
fi
grep -Eq '^online-mode[[:space:]]*=[[:space:]]*true[[:space:]]*$' \
    server.properties || die 'Set online-mode=true in server.properties.'
if grep -Eq '^enforce-secure-profile[[:space:]]*=[[:space:]]*false[[:space:]]*$' \
        server.properties; then
    die 'Set enforce-secure-profile=true in server.properties.'
fi

if ! valid_agent "$AGENT"; then
    command -v curl >/dev/null || die 'Install curl to download authlib-injector.'
    partial=$(mktemp ./authlib-injector.XXXXXX)
    trap 'rm -f -- "$partial"' EXIT
    curl --fail --location --proto '=https' --proto-redir '=https' \
        --retry 3 --connect-timeout 20 --max-time 300 "$DOWNLOAD" -o "$partial"
    valid_agent "$partial" || die 'authlib-injector SHA-256 verification failed.'
    mv -f -- "$partial" "$AGENT"
    trap - EXIT
fi

exec "$JAVA" "-Xms$MIN_RAM" "-Xmx$MAX_RAM" \
    "-javaagent:$AGENT=$AUTH_URL" -jar "$SERVER_JAR" nogui
```

## Windows CMD: `start-server.cmd`

Run `start-server.cmd`. Uses Windows PowerShell for downloading and hash checks.

```bat
@echo off
setlocal DisableDelayedExpansion
pushd "%~dp0" || exit /b 1

set "AUTH_URL=http://127.0.0.1:8080/api/yggdrasil/"
set "SERVER_JAR=server.jar"
set "JAVA=java"
set "MIN_RAM=1G"
set "MAX_RAM=2G"

set "AGENT=authlib-injector-1.2.8.jar"
set "DOWNLOAD=https://authlib-injector.yushi.moe/artifact/56/%AGENT%"
set "SHA256=9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10"

if not exist "%SERVER_JAR%" (
    echo Server JAR not found: "%SERVER_JAR%"
    goto fail
)
"%JAVA%" -version >nul 2>&1
if errorlevel 1 (
    echo Java was not found. Set JAVA above.
    goto fail
)

powershell.exe -NoProfile -Command ^
    "$ErrorActionPreference='Stop';" ^
    "Import-Module ($PSHOME + '/Modules/Microsoft.PowerShell.Utility');" ^
    "if (!(Test-Path -LiteralPath 'server.properties')) {" ^
    "  $defaults=@('online-mode=true','enforce-secure-profile=true');" ^
    "  Set-Content -LiteralPath 'server.properties' -Value $defaults -Encoding ASCII;" ^
    "};" ^
    "$p=Get-Content -LiteralPath 'server.properties' -Raw;" ^
    "if ($p -notmatch '(?m)^online-mode\s*=\s*true\s*$') {" ^
    "  throw 'Set online-mode=true in server.properties.';" ^
    "};" ^
    "if ($p -match '(?m)^enforce-secure-profile\s*=\s*false\s*$') {" ^
    "  throw 'Set enforce-secure-profile=true in server.properties.';" ^
    "};" ^
    "$valid=$false;" ^
    "if (Test-Path -LiteralPath $env:AGENT -PathType Leaf) {" ^
    "  $valid=(Get-FileHash -LiteralPath $env:AGENT -Algorithm SHA256).Hash" ^
    "    -eq $env:SHA256;" ^
    "};" ^
    "if (!$valid) {" ^
    "  [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
    "  $temp=Join-Path (Get-Location) ([IO.Path]::GetRandomFileName());" ^
    "  try {" ^
    "    Invoke-WebRequest -UseBasicParsing -Uri $env:DOWNLOAD -OutFile $temp" ^
    "      -TimeoutSec 300;" ^
    "    if ((Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash" ^
    "        -ne $env:SHA256) { throw 'authlib-injector SHA-256 check failed.' };" ^
    "    Move-Item -LiteralPath $temp -Destination $env:AGENT -Force;" ^
    "  } finally {" ^
    "    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp };" ^
    "  };" ^
    "}"
if errorlevel 1 goto fail

"%JAVA%" -Xms%MIN_RAM% -Xmx%MAX_RAM% ^
    "-javaagent:%AGENT%=%AUTH_URL%" -jar "%SERVER_JAR%" nogui
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

:fail
popd
exit /b 1
```

These scripts target servers started with `java -jar`, including older Forge
versions. If a mod loader uses an argument-file startup script, add the same
`-javaagent` JVM argument to that loader's existing startup configuration.
