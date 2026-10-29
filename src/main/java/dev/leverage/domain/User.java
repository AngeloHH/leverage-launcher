package dev.leverage.domain;

import java.time.Instant;
import java.util.UUID;

public record User(UUID id, String username, String email, Role role,
        UserStatus status, Instant createdAt, Instant updatedAt) { }
