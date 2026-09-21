package io.jenkins.plugins.awsbuildsessionlogger.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Plain-Java unit tests for {@link AuthCore} using the hand-written {@link FakeStsAssumeRole}. */
class AuthCoreTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/non_prod";

    private static AwsCredentials sampleCreds() {
        return new AwsCredentials("AKIA_X", "secret", "token", Instant.parse("2026-07-24T13:00:00Z"));
    }

    @Test
    void buildsJkSessionNameAndTargetRole() {
        FakeStsAssumeRole fake = new FakeStsAssumeRole().returning(sampleCreds());
        AuthCore core = new AuthCore(fake);

        core.authenticate(ROLE, "myjob", 123);

        AssumeRoleRequest sent = fake.lastRequest();
        assertEquals(ROLE, sent.roleArn());
        assertEquals("jk-myjob-123", sent.sessionName().value());
    }

    @Test
    void returnsCredentialsFromPort() {
        AwsCredentials expected = sampleCreds();
        AuthCore core = new AuthCore(new FakeStsAssumeRole().returning(expected));

        assertSame(expected, core.authenticate(ROLE, "myjob", 1));
    }

    @Test
    void wrapsPortRuntimeFailureWithActionableMessage() {
        AuthCore core = new AuthCore(new FakeStsAssumeRole().throwing(new IllegalStateException("network down")));

        AssumeRoleException ex = assertThrows(AssumeRoleException.class, () -> core.authenticate(ROLE, "myjob", 42));
        assertTrue(ex.getMessage().contains(ROLE), "message should name the role");
        assertTrue(ex.getMessage().contains("jk-myjob-42"), "message should name the session");
    }

    @Test
    void propagatesAssumeRoleExceptionUnchanged() {
        AssumeRoleException original = new AssumeRoleException("access denied");
        AuthCore core = new AuthCore(new FakeStsAssumeRole().throwing(original));

        AssumeRoleException thrown = assertThrows(AssumeRoleException.class, () -> core.authenticate(ROLE, "myjob", 1));
        assertSame(original, thrown);
    }

    @Test
    void failsWhenPortReturnsNull() {
        AuthCore core = new AuthCore(new FakeStsAssumeRole().returning(null));
        assertThrows(AssumeRoleException.class, () -> core.authenticate(ROLE, "myjob", 1));
    }

    @Test
    void invalidIdentityFailsClosedBeforeCallingPort() {
        FakeStsAssumeRole fake = new FakeStsAssumeRole().returning(sampleCreds());
        AuthCore core = new AuthCore(fake);

        assertThrows(SessionNameException.class, () -> core.authenticate(ROLE, "  ", 1));
        assertTrue(fake.lastRequest() == null, "port must not be called when the identity is invalid");
    }
}
