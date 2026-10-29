package dev.leverage.domain;

public enum Role {
    USER(0), MODERATOR(1), ADMIN(2);

    private final int level;

    Role(int level) {
        this.level = level;
    }

    public boolean atLeast(Role other) {
        return level >= other.level;
    }
}
