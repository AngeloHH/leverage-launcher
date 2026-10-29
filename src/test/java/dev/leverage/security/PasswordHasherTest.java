package dev.leverage.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    @Test
    void createsSaltedVerifiableHashes() {
        PasswordHasher hasher = new PasswordHasher(10_000);
        char[] password = "a-secure-test-password".toCharArray();

        String first = hasher.hash(password);
        String second = hasher.hash(password);

        assertNotEquals(first, second);
        assertTrue(hasher.verify(password, first));
        assertFalse(hasher.verify("wrong-password".toCharArray(), first));
    }
}
