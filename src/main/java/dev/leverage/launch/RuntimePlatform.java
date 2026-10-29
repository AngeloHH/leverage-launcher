package dev.leverage.launch;

import java.util.List;
import java.util.Locale;
import java.util.Map;

// Map Mojang runtime IDs, rule OS names and Java architectures in one place.
public enum RuntimePlatform {
    WINDOWS_X64("windows-x64", "windows", "amd64"), WINDOWS_X86("windows-x86",
            "windows", "x86"), WINDOWS_ARM64("windows-arm64", "windows", "aarch64"),
            MAC_X64("mac-os", "osx", "x86_64"), MAC_ARM64("mac-os-arm64", "osx",
            "aarch64"), LINUX_X64("linux", "linux", "amd64"), LINUX_X86("linux-i386",
            "linux", "x86"), LINUX_ARM64("linux-arm64", "linux", "aarch64");

    public static final List<String> MIRROR_DEFAULTS = List.of("windows-x64",
            "linux", "mac-os", "mac-os-arm64");
    private final String id, os, architecture;
    RuntimePlatform(String id, String os, String architecture) {
        this.id = id;
        this.os = os;
        this.architecture = architecture;
    }
    public String id() { return id; }
    public String os() { return os; }
    public String architecture() { return architecture; }
    public boolean isMac() { return os.equals("osx"); }
    public String javaPath() {
        return isMac() ? "jre.bundle/Contents/Home/bin/java"
                : os.equals("windows") ? "bin/java.exe" : "bin/java";
    }
    public String releasePath() {
        return isMac() ? "jre.bundle/Contents/Home/release" : "release";
    }
    public LaunchRules.Environment environment() {
        return new LaunchRules.Environment(os, architecture,
                System.getProperty("os.version", ""), Map.of());
    }
    // macOS can run Intel Java through Rosetta when no ARM runtime exists.
    public List<RuntimePlatform> candidates() {
        return this == MAC_ARM64 ? List.of(this, MAC_X64) : List.of(this);
    }
    public boolean wantsClassifier(String classifier) {
        String name = classifier.toLowerCase(Locale.ROOT);
        if (!name.startsWith("natives-")) return true;
        return switch (os) {
            case "windows" -> name.contains("windows");
            case "osx" -> name.contains("macos") || name.contains("osx")
                    || name.contains("mac-os");
            default -> name.contains("linux");
        };
    }
    public static RuntimePlatform current() {
        return detect(System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
    }
    public static RuntimePlatform detect(String osName, String archName) {
        // Installers and manifests reuse the same canonical platform identity.
        String os = osName.toLowerCase(Locale.ROOT),
                arch = archName.toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64"),
                wide = arch.contains("64");
        if (os.contains("mac") || os.contains("darwin")) return arm ? MAC_ARM64 : MAC_X64;
        if (os.contains("win")) return arm ? WINDOWS_ARM64
                : wide ? WINDOWS_X64 : WINDOWS_X86;
        if (os.contains("linux")) return arm ? LINUX_ARM64 : wide ? LINUX_X64 : LINUX_X86;
        throw new IllegalArgumentException("Unsupported platform: "
                + osName + " / " + archName);
    }
    public static RuntimePlatform fromId(String id) {
        String name = id.toLowerCase(Locale.ROOT);
        if (List.of("macos", "macos-x64", "osx",
                "osx-x64").contains(name)) return MAC_X64;
        if (List.of("macos-arm64", "osx-arm64").contains(name)) return MAC_ARM64;
        if (name.equals("linux-x64")) return LINUX_X64;
        for (RuntimePlatform platform : values())
                if (platform.id.equals(name)) return platform;
        throw new IllegalArgumentException("Unsupported runtime platform: " + id);
    }
}
