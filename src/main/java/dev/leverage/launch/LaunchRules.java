package dev.leverage.launch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

// The last matching Mojang rule wins; absent rules allow the entry.
public final class LaunchRules {
    private LaunchRules() { }

    public record Environment(String osName, String architecture,
            String osVersion, Map<String, Boolean> features) {
        public Environment { features = Map.copyOf(features); }
        public static Environment current() {
            return RuntimePlatform.current().environment();
        }
    }

    public static boolean allows(JsonObject value, Environment environment) {
        if (!value.has("rules")) return true;
        if (!value.get("rules").isJsonArray()) return false;
        boolean allowed = false;
        for (JsonElement element : value.getAsJsonArray("rules")) {
            if (!element.isJsonObject()) continue;
            JsonObject rule = element.getAsJsonObject();
            if (matches(rule, environment)) allowed = "allow".equals(
                    string(rule, "action"));
        }
        return allowed;
    }

    private static boolean matches(JsonObject rule, Environment environment) {
        if (rule.has("os")) {
            if (!rule.get("os").isJsonObject()) return false;
            JsonObject os = rule.getAsJsonObject("os");
            if (os.has("name") && !environment.osName().equals(string(os,
                    "name"))) return false;
            if (os.has("arch") && !regex(string(os, "arch"),
                    environment.architecture())) return false;
            if (os.has("version") && !regex(string(os, "version"),
                    environment.osVersion())) return false;
            if (os.has("versionRange")) {
                if (!os.get("versionRange").isJsonObject()) return false;
                JsonObject range = os.getAsJsonObject("versionRange");
                if (range.has("min") && compareVersion(environment.osVersion(),
                        string(range, "min")) < 0) return false;
                if (range.has("max") && compareVersion(environment.osVersion(),
                        string(range, "max")) >= 0) return false;
            }
        }
        if (rule.has("features")) {
            if (!rule.get("features").isJsonObject()) return false;
            for (var entry : rule.getAsJsonObject("features").entrySet()) {
                if (!entry.getValue().isJsonPrimitive() ||
                        !entry.getValue().getAsJsonPrimitive().isBoolean()) return false;
                if (environment.features().getOrDefault(entry.getKey(),
                        false) != entry.getValue().getAsBoolean()) return false;
            }
        }
        return true;
    }

    private static boolean regex(String expression, String value) {
        try { return expression != null && value.matches(expression); }
        catch (PatternSyntaxException exception) { return false; }
    }
    private static int compareVersion(String left, String right) {
        if (right == null) return -1;
        String[] a = left.split("\\D+"), b = right.split("\\D+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length && !a[i].isEmpty() ? number(a[i]) : 0;
            long y = i < b.length && !b[i].isEmpty() ? number(b[i]) : 0;
            int result = Long.compare(x, y);
            if (result != 0) return result;
        }
        return 0;
    }
    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return Long.MAX_VALUE;
        }
    }
    private static String string(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive()
                ? value.get(key).getAsString() : null;
    }
}
