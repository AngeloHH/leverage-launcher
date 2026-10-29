package dev.leverage.cli;

import dev.leverage.domain.User;
import dev.leverage.domain.UserStatus;
import dev.leverage.service.AuthService;
import dev.leverage.service.ServiceException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

// This console trusts its operator; never expose its input over the network.
public final class ServerConsole {
    private static final int MAXIMUM_LINE_LENGTH = 512;
    private static final String USAGE =
            "Usage: admin <email> | help. Ctrl+C stops the server.";

    private ServerConsole() { }

    public static Thread start(AuthService auth, InputStream input, PrintStream output) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        return Thread.ofPlatform().daemon().name("leverage-server-console").start(() ->
                run(auth, new InputStreamReader(input, StandardCharsets.UTF_8), output));
    }

    // EOF stops only this reader, not the HTTP server or its supplied input stream.
    public static void run(AuthService auth, Reader input, PrintStream output) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        output.println("Local console ready. " + USAGE);
        StringBuilder line = new StringBuilder();
        boolean excessive = false;
        boolean afterCarriageReturn = false;
        try {
            int character;
            while ((character = input.read()) != -1) {
                if (character == '\n' && afterCarriageReturn) {
                    afterCarriageReturn = false;
                    continue;
                }
                afterCarriageReturn = character == '\r';
                if (character == '\r' || character == '\n') {
                    processLine(auth, line.toString(), excessive, output);
                    line.setLength(0);
                    excessive = false;
                } else if (line.length()<MAXIMUM_LINE_LENGTH) {
                    line.append((char) character);
                } else {
                    // Drain the rest of this line without retaining an unbounded input in
                    // memory.
                    excessive = true;
                }
            }
            if (!line.isEmpty() || excessive) {
                processLine(auth, line.toString(), excessive, output);
            }
        } catch (IOException failure) {
            output.println("Console input was closed. The server is still running.");
        }
    }

    private static void processLine(AuthService auth, String line,
            boolean excessive, PrintStream output) {
        if (excessive) {
            output.println("Command is too long; maximum 512 characters. " + USAGE);
            return;
        }
        String command = line.strip();
        if (command.isEmpty()) {
            return;
        }
        if (command.chars().anyMatch(value -> Character.isISOControl(value)
                && value != '\t')) {
            output.println("The command contains invalid characters. " + USAGE);
            return;
        }
        String[] arguments = command.split("\\s+");
        if (arguments.length == 1 && arguments[0].equals("help")) {
            output.println(USAGE);
            output.println(
                    "admin <email> grants ADMIN to an existing account without changi"
                    + "ng its status or password.");
            return;
        }
        if (arguments.length != 2 || !arguments[0].equals("admin")) {
            output.println("Invalid command. " + USAGE);
            return;
        }
        try {
            User user = auth.promoteAdminFromConsole(arguments[1]);
            output.printf("Administrator: %s (%s).%n", user.username(), user.email());
            if (user.status() != UserStatus.ACTIVE) {
                output.println("Account status is unchanged: " + user.status()
                        + (". Promotion does not verify email addresses or lift "
                        + "restrictions."));
            }
        } catch (ServiceException failure) {
            output.printf("%s: %s%n", failure.code(), failure.getMessage());
        }
    }
}
