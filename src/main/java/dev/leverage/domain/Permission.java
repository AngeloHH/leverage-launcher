package dev.leverage.domain;

import java.util.EnumSet;
import java.util.Set;

public enum Permission {
    ACCOUNT_USE, USERS_READ, USERS_MODERATE, ROLES_MANAGE, SYSTEM_ADMIN;

    public static Set<Permission> forRole(Role role) {
        EnumSet<Permission> permissions = EnumSet.of(ACCOUNT_USE);
        // Higher roles retain the same account
        // permissions and add only their staff operations.
        if (role.atLeast(Role.MODERATOR)) {
            permissions.add(USERS_READ);
            permissions.add(USERS_MODERATE);
        }
        if (role.atLeast(Role.ADMIN)) {
            permissions.add(ROLES_MANAGE);
            permissions.add(SYSTEM_ADMIN);
        }
        return Set.copyOf(permissions);
    }
}
