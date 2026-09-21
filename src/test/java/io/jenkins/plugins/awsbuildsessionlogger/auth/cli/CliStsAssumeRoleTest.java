package io.jenkins.plugins.awsbuildsessionlogger.auth.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.awsbuildsessionlogger.auth.AssumeRoleException;
import io.jenkins.plugins.awsbuildsessionlogger.auth.AssumeRoleRequest;
import io.jenkins.plugins.awsbuildsessionlogger.auth.AuthCore;
import io.jenkins.plugins.awsbuildsessionlogger.auth.AwsCredentials;
import io.jenkins.plugins.awsbuildsessionlogger.auth.SessionName;
import io.jenkins.plugins.awsbuildsessionlogger.exec.FakeProcessRunner;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plain-Java unit tests for {@link CliStsAssumeRole} driven by a fake {@link FakeProcessRunner}. */
class CliStsAssumeRoleTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/non_prod";

    /** One tab-separated line as produced by {@code --query Credentials.[...] --output text}. */
    private static final String GOOD_OUTPUT =
            "AKIAEXAMPLE\tSECRET/EXAMPLE+VALUE\tSESSIONTOKENVALUE==\t2026-07-24T13:00:00+00:00\n";

    private static AssumeRoleRequest request() {
        return AssumeRoleRequest.of(ROLE, SessionName.forBuild("myjob", 123));
    }

    @Test
    void buildsExpectedCommandWithoutDuration() {
        FakeProcessRunner runner = new FakeProcessRunner().returning(0, GOOD_OUTPUT, "");
        new CliStsAssumeRole(runner).assumeRole(request());

        assertEquals(
                List.of(
                        "aws",
                        "sts",
                        "assume-role",
                        "--role-arn",
                        ROLE,
                        "--role-session-name",
                        "jk-myjob-123",
                        "--query",
                        "Credentials.[AccessKeyId,SecretAccessKey,SessionToken,Expiration]",
                        "--output",
                        "text"),
                runner.lastCommand());
    }

    @Test
    void buildsCommandWithDurationWhenPresent() {
        FakeProcessRunner runner = new FakeProcessRunner().returning(0, GOOD_OUTPUT, "");
        AssumeRoleRequest withDuration = AssumeRoleRequest.of(ROLE, SessionName.forBuild("myjob", 123), 3600);

        new CliStsAssumeRole(runner).assumeRole(withDuration);

        List<String> command = runner.lastCommand();
        assertTrue(command.contains("--duration-seconds"));
        assertEquals("3600", command.get(command.indexOf("--duration-seconds") + 1));
    }

    @Test
    void honoursConfiguredExecutableName() {
        FakeProcessRunner runner = new FakeProcessRunner().returning(0, GOOD_OUTPUT, "");
        new CliStsAssumeRole(runner, "/usr/local/bin/aws").assumeRole(request());

        assertEquals("/usr/local/bin/aws", runner.lastCommand().get(0));
    }

    @Test
    void parsesCredentialsFromSuccessOutput() {
        FakeProcessRunner runner = new FakeProcessRunner().returning(0, GOOD_OUTPUT, "");

        AwsCredentials creds = new CliStsAssumeRole(runner).assumeRole(request());

        assertEquals("AKIAEXAMPLE", creds.accessKeyId());
        assertEquals("SECRET/EXAMPLE+VALUE", creds.secretAccessKey());
        assertEquals("SESSIONTOKENVALUE==", creds.sessionToken());
        assertEquals(Instant.parse("2026-07-24T13:00:00Z"), creds.expiration());
    }

    @Test
    void nonZeroExitMapsToAssumeRoleExceptionWithContext() {
        FakeProcessRunner runner = new FakeProcessRunner()
                .returning(255, "", "An error occurred (AccessDenied) when calling the AssumeRole operation");

        AssumeRoleException ex =
                assertThrows(AssumeRoleException.class, () -> new CliStsAssumeRole(runner).assumeRole(request()));
        assertTrue(ex.getMessage().contains("255"), "should include the exit code");
        assertTrue(ex.getMessage().contains("AccessDenied"), "should surface stderr");
        assertTrue(ex.getMessage().contains(ROLE), "should name the role");
        assertTrue(ex.getMessage().contains("jk-myjob-123"), "should name the session");
    }

    @Test
    void malformedOutputMapsToAssumeRoleException() {
        FakeProcessRunner runner = new FakeProcessRunner().returning(0, "only\ttwo\n", "");
        assertThrows(AssumeRoleException.class, () -> new CliStsAssumeRole(runner).assumeRole(request()));
    }

    @Test
    void emptyOutputMapsToAssumeRoleException() {
        FakeProcessRunner runner = new FakeProcessRunner().returning(0, "   \n", "");
        assertThrows(AssumeRoleException.class, () -> new CliStsAssumeRole(runner).assumeRole(request()));
    }

    @Test
    void unparseableExpirationMapsToAssumeRoleException() {
        FakeProcessRunner runner = new FakeProcessRunner().returning(0, "a\tb\tc\tnot-a-date\n", "");
        assertThrows(AssumeRoleException.class, () -> new CliStsAssumeRole(runner).assumeRole(request()));
    }

    @Test
    void executionFailureIsMappedToAssumeRoleException() {
        // The process could not run at all; the execution-layer exception must not leak out.
        FakeProcessRunner runner = new FakeProcessRunner().failingExecution("aws: command not found");

        AssumeRoleException ex =
                assertThrows(AssumeRoleException.class, () -> new CliStsAssumeRole(runner).assumeRole(request()));
        assertTrue(ex.getMessage().contains(ROLE));
    }

    @Test
    void authCoreIsUnchangedAndWorksWithCliImplementation() {
        // AuthCore is constructed exactly as before; only the StsAssumeRole implementation swaps.
        FakeProcessRunner runner = new FakeProcessRunner().returning(0, GOOD_OUTPUT, "");
        AuthCore core = new AuthCore(new CliStsAssumeRole(runner));

        AwsCredentials creds = core.authenticate(ROLE, "myjob", 123);

        assertEquals("AKIAEXAMPLE", creds.accessKeyId());
        assertTrue(runner.lastCommand().contains("jk-myjob-123"), "session name flowed through to the CLI");
    }

    @Test
    void propagatesDomainAssumeRoleExceptionUnchanged() {
        // Sanity: a genuine AssumeRoleException surfaces as itself (AuthCore passes it through).
        FakeProcessRunner runner = new FakeProcessRunner().returning(1, "", "boom");
        AssumeRoleException direct =
                assertThrows(AssumeRoleException.class, () -> new CliStsAssumeRole(runner).assumeRole(request()));
        assertSame(AssumeRoleException.class, direct.getClass());
    }
}
