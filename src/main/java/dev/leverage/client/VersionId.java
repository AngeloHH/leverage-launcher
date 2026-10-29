package dev.leverage.client;

// Version IDs are safe filenames, not just dotted numbers; Mojang uses spaces.
public final class VersionId {
    private VersionId() { }
    public static boolean isValid(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9 ._+~-]{0,149}")
                && !id.contains("..") && !id.endsWith(".") && !id.endsWith(" ")
                && !id.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?");
    }
}
