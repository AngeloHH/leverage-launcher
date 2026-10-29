package dev.leverage.launch;

import static dev.leverage.i18n.I18n.t;

import java.util.List;

public record GameMemory(int minimumMb, int maximumMb) {
    public static final GameMemory DEFAULT = new GameMemory(256, 2048);

    public GameMemory {
        if (minimumMb <= 0 || maximumMb <= 0 || minimumMb > maximumMb)
                throw new IllegalArgumentException(t("memory.invalidRange"));
    }

    public List<String> jvmArguments() {
        return List.of("-Xms" + minimumMb + "M", "-Xmx" + maximumMb + "M");
    }
}
