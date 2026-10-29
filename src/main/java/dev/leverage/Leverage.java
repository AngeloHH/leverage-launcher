package dev.leverage;

import com.google.gson.JsonParser;
import dev.leverage.config.AppConfig;
import dev.leverage.client.LauncherUpdateClient;
import dev.leverage.client.LocalVersionCatalog;
import dev.leverage.cli.ServerConsole;
import dev.leverage.db.Database;
import dev.leverage.http.ApiServer;
import dev.leverage.mail.ConsoleRecoveryMailer;
import dev.leverage.mail.MailerFactory;
import dev.leverage.mail.RecoveryMailer;
import dev.leverage.launch.AuthlibInjectorManager;
import dev.leverage.mirror.MirrorConfig;
import dev.leverage.mirror.MirrorRepository;
import dev.leverage.mirror.MirrorSynchronizer;
import dev.leverage.security.PasswordHasher;
import dev.leverage.service.AuthService;
import dev.leverage.service.LauncherVersionService;
import dev.leverage.service.ModArtifactService;
import dev.leverage.service.ModPolicyService;
import dev.leverage.service.ServiceException;
import dev.leverage.service.InputValidation;
import dev.leverage.skin.TextureService;
import dev.leverage.skin.YggdrasilSessionService;
import dev.leverage.ui.ComponentGallery;
import dev.leverage.ui.LauncherWindow;

import java.io.Console;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

public final class Leverage {
    private static final String VERSION = "0.1.0";

    private Leverage() { }

    // =========================================================================
    // PROCESS ENTRY AND COMMAND ROUTING
    // =========================================================================

    public static void main(String[] args) {
        try {
            int exitCode = run(args);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (ServiceException exception) {
            System.err.printf("%s: %s%n", exception.code(), exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("No se pudo iniciar Leverage: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0) {
            LauncherWindow.open();
            return 0;
        }
        if (args[0].equals("help") || args[0].equals("--help")) {
            usage();
            return 0;
        }
        if (args[0].equals("--version") || args[0].equals("version")) {
            System.out.println("Leverage " + VERSION);
            return 0;
        }
        if (args[0].equals("ui-gallery") || args[0].equals("--ui-gallery")) {
            ComponentGallery.open();
            return 0;
        }
        if (args[0].equals("launcher") || args[0].equals("--gui")) {
            LauncherWindow.open();
            return 0;
        }
        if (args[0].equals("launcher-hash")) {
            String requestedFile = optionValue(args, "--file");
            Path launcher = requestedFile == null
                    ? LauncherUpdateClient.runningLauncherPath()
                    : Path.of(requestedFile).toAbsolutePath().normalize();
            System.out.println(LauncherUpdateClient.sha1(launcher));
            return 0;
        }
        if (args[0].equals("local-versions")) {
            return printLocalVersions(args);
        }
        if (args[0].equals("admin")) return promoteAdmin(args);
        if (args[0].equals("--nogui") && optionValue(args, "--host") == null) {
            if (optionValue(args, "--mirror") != null) {
                throw new IllegalArgumentException("--mirror requiere --host <URL>.");
            }
            return printLocalVersions(args);
        }

        Path configPath = option(args, "--config", Path.of("config",
                "leverage.properties"));
        AppConfig config = AppConfig.load(configPath);

        if (args[0].equals("authlib-setup")) {
            Path installed = authlibManager(config).ensureInstalled();
            System.out.println("authlib-injector "
                    + AuthlibInjectorManager.OFFICIAL_VERSION
                    + " verificado en " + installed);
            return 0;
        }
        if (args[0].equals("authlib-args")) {
            String requestedHost = optionValue(args, "--host");
            String requestedSide = optionValue(args, "--side");
            if (requestedHost == null || requestedSide == null) {
                throw new IllegalArgumentException(
                        "authlib-args requiere --host <URL> y --side client|server.");
            }
            URI publicHost = validatePublicHost(requestedHost);
            AuthlibInjectorManager manager = authlibManager(config);
            manager.ensureInstalled();
            String metadata = fetchYggdrasilMetadata(
                    AuthlibInjectorManager.apiRoot(publicHost));
            System.out.println(
                    "Argumentos JVM; deben ir antes de -jar o de la clase principal:");
            manager.jvmArguments(publicHost, metadata, AuthlibInjectorManager.Side.from(
                    requestedSide)).forEach(System.out::println);
            return 0;
        }

        String hostValue = optionValue(args, "--host");
        String mirrorValue = optionValue(args, "--mirror");
        if (mirrorValue != null && hostValue == null) {
            throw new IllegalArgumentException("--mirror requiere --host <URL>.");
        }
        if (hostValue != null) {
            if (!hasOption(args, "--nogui")) {
                throw new IllegalArgumentException(
                        "El modo host actual requiere --nogui.");
            }
            URI publicHost = validatePublicHost(hostValue);
            MirrorRepository mirror = null;
            if (mirrorValue != null) {
                MirrorConfig mirrorConfig = MirrorConfig.load(Path.of(mirrorValue));
                String output = optionValue(args, "--mirror-output");
                if (output != null) mirrorConfig = new MirrorConfig(Path.of(
                        output).toAbsolutePath().normalize(), mirrorConfig.versions(),
                        mirrorConfig.platforms(), mirrorConfig.javaRuntime());
                mirror = new MirrorRepository(mirrorConfig.output());
                var result = new MirrorSynchronizer(mirrorConfig,
                        mirror, publicHost).synchronize();
                System.out.printf("Mirror listo: %d versiones, %d runtimes Java, "
                        + "%d descargas, %d archivos reutilizados.%n",
                        result.versions(), result.javaRuntimes(),
                        result.downloadedFiles(), result.reusedFiles());
                if (hasOption(args, "--mirror-only")) return 0;
            }
            Database database = new Database(config.databasePath());
            database.initialize();
            HostBinding binding = bindingFor(publicHost, config);
            return runServer(config, database, mirror, binding, publicHost);
        }
        Database database = new Database(config.databasePath());
        database.initialize();

        return switch (args[0]) {
            case "server" -> runServer(config, database, null,
                    new HostBinding(config.bindAddress(), config.port()),
                    URI.create("http://" + config.bindAddress() + ":" + config.port()));
            case "bootstrap-admin" -> bootstrapAdmin(config, database);
            default -> {
                System.err.println("Comando desconocido: " + args[0]);
                usage();
                yield 2;
            }
        };
    }

    // =========================================================================
    // SERVER LIFECYCLE AND LOCAL ADMINISTRATION
    // =========================================================================

    private static int runServer(AppConfig config, Database database,
            MirrorRepository mirror, HostBinding binding,
            URI publicHost) throws Exception {
        // Serve a previously generated mirror without downloading again on every API
        // start.
        Path storedMirror = Path.of("mirror").toAbsolutePath().normalize();
        if (mirror == null && java.nio.file.Files.isRegularFile(storedMirror.resolve(
                "versions/index.json"))) mirror = new MirrorRepository(storedMirror);
        RecoveryMailer mailer = MailerFactory.create(config.mail());
        AuthService auth = authService(config, database, mailer);
        bootstrapAdminFromEnvironment(auth);
        TextureService textures = new TextureService(database,
                config.textureStoragePath(), config.yggdrasilKeyPath(),
                publicHost, Clock.systemUTC());
        YggdrasilSessionService yggdrasilSessions = new YggdrasilSessionService(database,
                auth, textures, Clock.systemUTC(), config.yggdrasilJoinTtl());
        ModArtifactService modArtifacts = new ModArtifactService(database,
                config.modStoragePath(), Clock.systemUTC());
        ModPolicyService modPolicies = new ModPolicyService(database,
                config.modPolicyPath(), Clock.systemUTC(),
                config.modSuspensionDuration(), modArtifacts);
        LauncherVersionService launcherVersions = new LauncherVersionService(
                config.launcherVersionPolicyPath());

        try (ApiServer server = new ApiServer(binding.address(), binding.port(),
                auth, Clock.systemUTC(), mirror, textures, yggdrasilSessions,
                modPolicies, launcherVersions, modArtifacts)) {
            server.start();
            System.out.printf("Leverage %s escuchando en http://%s:%d%n",
                    VERSION, binding.address(), server.port());
            System.out.println("URL pública declarada: " + publicHost);
            System.out.println("Servicio Yggdrasil: " + publicHost + "/api/yggdrasil/");
            System.out.println("Política de mods: " + config.modPolicyPath());
            System.out.println("Almacenamiento de mods: " + config.modStoragePath());
            System.out.println("Versiones del launcher: "
                    + config.launcherVersionPolicyPath());
            ServerConsole.start(auth, System.in, System.out);
            if (mirror != null) {
                System.out.println("Catálogo del mirror: " + publicHost
                        + "/mirror/versions/index.json");
                System.out.println("Manifest clásico: " + publicHost
                        + "/game/version_manifest.json");
                System.out.println("Manifest v2: " + publicHost
                        + "/game/version_manifest_v2.json");
            }
            CountDownLatch stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(
                    stopped::countDown));
            stopped.await();
        }
        return 0;
    }

    private static int promoteAdmin(String[] args) throws Exception {
        String email = null;
        Path configPath = Path.of("config", "leverage.properties");
        boolean configSeen = false;
        for (int index = 1; index < args.length; index++) {
            String flag = args[index];
            if (!flag.equals("--email") && !flag.equals("--config"))
                    throw new IllegalArgumentException(
                    "admin solo acepta --email y --config.");
            if (index + 1 >= args.length || args[index + 1].startsWith("--"))
                    throw new IllegalArgumentException("Falta el valor para " + flag);
            String value = args[++index];
            if (flag.equals("--email")) {
                if (email != null) throw new IllegalArgumentException(
                        "No repitas --email.");
                email = value;
            } else {
                if (configSeen) throw new IllegalArgumentException(
                        "No repitas --config.");
                configSeen = true;
                configPath = Path.of(value);
            }
        }
        email = InputValidation.email(email);
        if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException(
                "Usa --config con el archivo de configuración existente de tu ser"
                + "vidor.");
        AppConfig config = AppConfig.load(configPath);
        Database database = Database.existing(config.databasePath());
        // No initialize(): a promotion is not permission to create or migrate a server
        // database.
        var admin = authService(config, database,
                new ConsoleRecoveryMailer()).promoteAdminFromConsole(email);
        System.out.println("Rol ADMIN confirmado para "
                + admin.username() + " (" + admin.id() + ").");
        if (admin.status() != dev.leverage.domain.UserStatus.ACTIVE) System.out.println(
                "El estado de la cuenta sigue siendo " + admin.status()
                + "; no se ha verificado ni levantado ninguna suspensión.");
        return 0;
    }

    private static int bootstrapAdmin(AppConfig config, Database database) {
        String username = environmentOrPrompt("LEVERAGE_BOOTSTRAP_USERNAME",
                "Usuario administrador: ", false);
        String email = environmentOrPrompt("LEVERAGE_BOOTSTRAP_EMAIL",
                "Correo administrador: ", false);
        String password = environmentOrPrompt("LEVERAGE_BOOTSTRAP_PASSWORD",
                "Contraseña administrador: ", true);
        if (username == null || email == null || password == null) {
            System.err.println(
                    "Se necesita una consola interactiva o las tres variables LEVERAG"
                    + "E_BOOTSTRAP_*.");
            return 2;
        }
        try {
            var admin = authService(config, database,
                    new ConsoleRecoveryMailer()).createBootstrapAdmin(username,
                    email, password);
            System.out.println("Administrador creado: " + admin.username()
                    + " (" + admin.id() + ")");
            return 0;
        } finally {
            password = null;
        }
    }

    private static void bootstrapAdminFromEnvironment(AuthService auth) {
        String username = System.getenv("LEVERAGE_BOOTSTRAP_USERNAME");
        String email = System.getenv("LEVERAGE_BOOTSTRAP_EMAIL");
        String password = System.getenv("LEVERAGE_BOOTSTRAP_PASSWORD");
        if (username == null && email == null && password == null) {
            return;
        }
        if (username == null || email == null || password == null) {
            throw new IllegalArgumentException(
                    "Deben definirse juntas las tres variables LEVERAGE_BOOTSTRAP_*.");
        }
        try {
            var admin = auth.createBootstrapAdmin(username, email, password);
            System.out.println("Administrador inicial creado: " + admin.username());
        } catch (ServiceException exception) {
            if (!exception.code().equals("ADMIN_EXISTS")) {
                throw exception;
            }
        }
    }

    private static AuthService authService(AppConfig config,
            Database database, RecoveryMailer mailer) {
        return new AuthService(database, PasswordHasher.production(), mailer,
                Clock.systemUTC(), config.sessionTtl(), config.deviceSessionTtl(),
                config.gameTicketTtl(), config.emailVerificationTtl(),
                config.passwordRecoveryTtl(), config.registrationEnabled());
    }

    private static AuthlibInjectorManager authlibManager(AppConfig config) {
        return new AuthlibInjectorManager(config.authlibInjectorPath(),
                config.authlibInjectorUrl(), config.authlibInjectorSha256());
    }

    private static String fetchYggdrasilMetadata(URI apiRoot) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(
                10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(apiRoot).timeout(Duration.ofSeconds(
                20)).header("Accept", "application/json").GET().build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalArgumentException("El servicio Yggdrasil respondió HTTP "
                    + response.statusCode() + ".");
        }
        if (!JsonParser.parseString(response.body()).isJsonObject()) {
            throw new IllegalArgumentException(
                    "El servicio Yggdrasil no devolvió metadatos JSON válidos.");
        }
        return response.body();
    }

    // =========================================================================
    // LOCAL CATALOG, OPTIONS, AND HOST VALIDATION
    // =========================================================================

    private static Path option(String[] args, String name, Path fallback) {
        String value = optionValue(args, name);
        return value == null ? fallback : Path.of(value);
    }

    private static String optionValue(String[] args, String name) {
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals(name)) {
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Falta el valor para " + name);
                }
                return args[index + 1];
            }
        }
        return null;
    }

    private static boolean hasOption(String[] args, String name) {
        return Arrays.asList(args).contains(name);
    }

    private static int printLocalVersions(String[] args) throws Exception {
        String requestedDirectory = optionValue(args, "--minecraft");
        Path minecraftDirectory = requestedDirectory == null
                ? LocalVersionCatalog.defaultMinecraftDirectory()
                : Path.of(requestedDirectory).toAbsolutePath().normalize();
        LocalVersionCatalog.Catalog catalog = new LocalVersionCatalog(
                minecraftDirectory).scan();
        System.out.printf("Versiones locales disponibles en %s: %d%n",
                catalog.minecraftDirectory(), catalog.versions().size());
        for (LocalVersionCatalog.InstalledVersion version : catalog.versions()) {
            String inheritance = version.inheritsFrom() == null ? ""
                    : " (hereda de " + version.inheritsFrom() + ")";
            System.out.printf("- %s [%s]%s%n", version.id(), version.type(), inheritance);
        }
        if (catalog.ignoredEntries() > 0) {
            System.out.printf("Se ignoraron %d entradas dañadas o incompletas.%n",
                    catalog.ignoredEntries());
        }
        return 0;
    }

    private static URI validatePublicHost(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("--host no contiene una URL válida.",
                    exception);
        }
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null || (!scheme.equalsIgnoreCase("http")
                && !scheme.equalsIgnoreCase("https")) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath()
                != null && !uri.getPath().isBlank() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException(
                    "--host debe ser una URL HTTP/HTTPS sin ruta adicional.");
        }
        String normalized = uri.toString();
        return URI.create(normalized.endsWith("/") ? normalized.substring(0,
                normalized.length() - 1) : normalized);
    }

    private static HostBinding bindingFor(URI publicHost, AppConfig config) {
        if (publicHost.getScheme().equalsIgnoreCase("http")) {
            int port = publicHost.getPort() >= 0 ? publicHost.getPort() : 80;
            return new HostBinding(publicHost.getHost(), port);
        }
        System.out.printf("HTTPS público; el proceso interno usará http://%s:%d.%n",
                config.bindAddress(), config.port());
        return new HostBinding(config.bindAddress(), config.port());
    }

    private static String environmentOrPrompt(String variable,
            String prompt, boolean secret) {
        String environment = System.getenv(variable);
        if (environment != null && !environment.isBlank()) {
            return environment;
        }
        Console console = System.console();
        if (console == null) {
            return null;
        }
        if (secret) {
            char[] value = console.readPassword(prompt);
            if (value == null) {
                return null;
            }
            try {
                return new String(value);
            } finally {
                Arrays.fill(value, '\0');
            }
        }
        return console.readLine(prompt);
    }

    private static void usage() {
        System.out.println(
        """
                Leverage 0.1.0

                Usage:
                  java -jar leverage.jar
                  java -jar leverage.jar launcher
                  java -jar leverage.jar --nogui --host <URL> [--mirror \
                <version-json-path>] [--mirror-output <directory>]
                  java -jar leverage.jar --nogui
                  java -jar leverage.jar bootstrap-admin [--config \
                config/leverage.properties]
                  java -jar leverage.jar admin --email <email> [--config \
                config/leverage.properties]
                  java -jar leverage.jar authlib-setup [--config \
                config/leverage.properties]
                  java -jar leverage.jar authlib-args --host <URL> --side client|server
                  java -jar leverage.jar launcher-hash [--file leverage.jar]
                  java -jar leverage.jar local-versions [--minecraft <directory>]
                  java -jar leverage.jar ui-gallery
                  java -jar leverage.jar --version

                --nogui --host starts the headless server and serves JSON responses.
                In the server console: admin <email> assigns ADMIN to an existing account.
                The standalone admin command requires an existing configuration and \
                database.
                --nogui without --host lists available local versions without connecting \
                to the server.
                With no arguments, opens the launcher's graphical interface.
                authlib-setup downloads the configured agent and verifies its SHA-256.
                authlib-args prepares JVM arguments for a Minecraft client or server.
                ui-gallery opens the launcher's graphical component gallery.
                """
        );
    }

    private record HostBinding(String address, int port) {
    }
}
