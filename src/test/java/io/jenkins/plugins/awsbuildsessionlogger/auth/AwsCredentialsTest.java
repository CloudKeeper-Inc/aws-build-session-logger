package io.jenkins.plugins.awsbuildsessionlogger.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Plain-Java unit tests for {@link AwsCredentials}; no Jenkins, no AWS. */
class AwsCredentialsTest {

    private static final Instant EXPIRY = Instant.parse("2026-07-24T12:00:00Z");

    private static Clock fixedAt(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private static AwsCredentials creds() {
        return new AwsCredentials("AKIA_TEST", "secret-value", "session-token-value", EXPIRY);
    }

    @Test
    void notExpiredBeforeExpiry() {
        assertFalse(creds().isExpired(fixedAt(EXPIRY.minusSeconds(1))));
    }

    @Test
    void expiredExactlyAtExpiry() {
        assertTrue(creds().isExpired(fixedAt(EXPIRY)));
    }

    @Test
    void expiredAfterExpiry() {
        assertTrue(creds().isExpired(fixedAt(EXPIRY.plusSeconds(1))));
    }

    @Test
    void expiresWithinWindow() {
        Clock now = fixedAt(EXPIRY.minusSeconds(60));
        assertTrue(creds().expiresWithin(Duration.ofSeconds(90), now), "expiry is inside the 90s window");
        assertFalse(creds().expiresWithin(Duration.ofSeconds(30), now), "expiry is outside the 30s window");
    }

    @Test
    void toStringRedactsSecrets() {
        String s = creds().toString();
        assertTrue(s.contains("AKIA_TEST"), "access key id is an identifier, shown for debugging");
        assertFalse(s.contains("secret-value"), "secret access key must be redacted");
        assertFalse(s.contains("session-token-value"), "session token must be redacted");
    }

    @Test
    void equalityIsValueBased() {
        assertEquals(creds(), creds());
        assertEquals(creds().hashCode(), creds().hashCode());
    }

    @Test
    void rejectsBlankFields() {
        assertThrows(IllegalArgumentException.class, () -> new AwsCredentials(" ", "s", "t", EXPIRY));
        assertThrows(IllegalArgumentException.class, () -> new AwsCredentials("a", " ", "t", EXPIRY));
        assertThrows(IllegalArgumentException.class, () -> new AwsCredentials("a", "s", " ", EXPIRY));
    }

    @Test
    void rejectsNullExpiration() {
        assertThrows(NullPointerException.class, () -> new AwsCredentials("a", "s", "t", null));
    }
}
