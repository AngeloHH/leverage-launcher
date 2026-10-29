# Leverage

Leverage is a Minecraft launcher with its own account and authentication service.
Players sign in through the launcher and use that identity, including their skin,
on Minecraft servers connected to the same Leverage service.

Server owners manage accounts, publish news and decide which mods and resource
packs are allowed. The launcher checks those rules before starting Minecraft and
watches for changes while the game is running. Each server and game version has
its own instance directory.

Download mirrors are built from Minecraft version JSON files. The CLI downloads
the declared dependencies, checks their hashes and reuses existing files.

The desktop client is written in Java with Swing. The same JAR runs the client,
HTTP service and command-line tools. It supports Windows, Linux and macOS.

## Build

Use Java 21 or later. From the project root:

```powershell
.\gradlew.bat shadowJar
```

On Linux or macOS: `sh ./gradlew shadowJar`.
The resulting JAR is `build/libs/leverage.jar`.

## Launcher

```sh
java -jar build/libs/leverage.jar
```

Select your Leverage service in the launcher and sign in, or use offline mode
for local play. To run from source, use `.\gradlew.bat run` on Windows or
`sh ./gradlew run` on Linux and macOS.

## Leverage service

```sh
java -jar build/libs/leverage.jar --nogui --host http://127.0.0.1:8080
```

This starts the account API at `http://127.0.0.1:8080`. Stop it with `Ctrl+C`.
To change its settings, copy `config/leverage.example.properties` to
`config/leverage.properties`. Verification and recovery messages are printed in
the console until SMTP is configured.

Minecraft server setup and startup scripts are in [SERVER.md](SERVER.md).

## CLI

```sh
java -jar build/libs/leverage.jar help
java -jar build/libs/leverage.jar local-versions
java -jar build/libs/leverage.jar bootstrap-admin
```

These commands list CLI usage, list installed versions and create the first
administrator, respectively. To promote an existing account, type this in the
running service's console:

```text
admin user@example.com
```

To build a mirror from a version descriptor and start serving it:

```sh
java -jar build/libs/leverage.jar --nogui --host http://127.0.0.1:8080 --mirror version.json
```

Replace `version.json` with the descriptor's path. Files are stored in `mirror/`;
the catalog is served at `/mirror/versions/index.json`. Add `--mirror-only` to
download the files without starting the service.

Project origins and third-party components are listed in [NOTICE.md](NOTICE.md).
