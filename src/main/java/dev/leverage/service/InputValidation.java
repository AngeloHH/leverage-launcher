package dev.leverage.service;

import java.util.Locale;
import java.util.regex.Pattern;

public final class InputValidation {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern EMAIL = Pattern.compile(
            "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private InputValidation() { }

    public static String username(String value) {
        String clean = required(value, "username").trim();
        if (!USERNAME.matcher(clean).matches()) {
            throw ServiceException.badRequest("INVALID_USERNAME",
                    "El usuario debe tener entre 3 y 16 caracteres: "
                    + "letras, números o guion bajo.");
        }
        return clean;
    }

    public static String normalizedUsername(String value) {
        return username(value).toLowerCase(Locale.ROOT);
    }

    public static String email(String value) {
        String clean = required(value, "email").trim();
        if (clean.length() > 254 || !EMAIL.matcher(clean).matches()) {
            throw ServiceException.badRequest("INVALID_EMAIL",
                    "El correo no tiene un formato válido.");
        }
        return clean;
    }

    public static String normalizedEmail(String value) {
        return email(value).toLowerCase(Locale.ROOT);
    }

    public static char[] password(String value) {
        String clean = required(value, "password");
        int codePoints = clean.codePointCount(0, clean.length());
        if (codePoints < 8 || codePoints > 128) {
            throw ServiceException.badRequest("INVALID_PASSWORD",
                    "La contraseña debe tener entre 8 y 128 caracteres.");
        }
        return clean.toCharArray();
    }

    public static String token(String value) {
        String clean = required(value, "token").trim();
        if (clean.length() < 40 || clean.length() > 128) {
            throw ServiceException.badRequest("INVALID_TOKEN",
                    "El token no tiene un formato válido.");
        }
        return clean;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ServiceException.badRequest("MISSING_FIELD",
                    "El campo '" + field + "' es obligatorio.");
        }
        return value;
    }
}
