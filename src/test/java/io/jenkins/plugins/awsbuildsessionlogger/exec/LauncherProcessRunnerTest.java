package io.jenkins.plugins.awsbuildsessionlogger.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.TaskListener;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for the launcher-backed runner — the change that moves the AssumeRole subprocess off the
 * controller and onto the node that will use the credentials.
 *
 * <p>Deliberately mirrors {@link DefaultProcessRunnerTest} case for case. The two implementations must
 * be interchangeable from {@code CliStsAssumeRole}'s point of view: same capture semantics, same
 * exit-code reporting, same failure translation. A divergence here would mean authentication behaved
 * differently depending on where it ran.
 *
 * <p>POSIX-only, matching the sibling test.
 */
@WithJenkins
@DisabledOnOs(OS.WINDOWS)
class LauncherProcessRunnerTest {

    @Test
    void capturesStdoutStderrAndNonZeroExitSeparately(JenkinsRule j) {
        ProcessResult result = runner(j).run(List.of("sh", "-c", "printf out; printf err 1>&2; exit 3"));

        assertEquals("out", result.stdout());
        assertEquals("err", result.stderr());
        assertEquals(3, result.exitCode());
        assertFalse(result.succeeded());
    }

    @Test
    void reportsSuccessOnZeroExit(JenkinsRule j) {
        ProcessResult result = runner(j).run(List.of("sh", "-c", "printf hi"));

        assertEquals("hi", result.stdout());
        assertEquals("", result.stderr());
        assertTrue(result.succeeded());
    }

    @Test
    void throwsWhenExecutableDoesNotExist(JenkinsRule j) {
        assertThrows(
                ProcessExecutionException.class,
                () -> runner(j).run(List.of("aws-build-session-logger-no-such-binary-42")));
    }

    @Test
    void rejectsEmptyCommand(JenkinsRule j) {
        assertThrows(IllegalArgumentException.class, () -> runner(j).run(List.of()));
    }

    @Test
    void appliesEnvironmentOverridesToTheChild(JenkinsRule j) {
        ProcessResult result = runner(j).run(echo("BSL_TEST_VAR"), Map.of("BSL_TEST_VAR", "overridden"));

        assertEquals("overridden", result.stdout());
        assertTrue(result.succeeded());
    }

    @Test
    void rejectsNullEnvironmentValues(JenkinsRule j) {
        // DefaultProcessRunner uses null to mean "unset this"; the Launcher API cannot express that, so
        // this implementation refuses rather than silently ignoring the caller's intent.
        Map<String, String> withNull = new java.util.HashMap<>();
        withNull.put("BSL_TEST_VAR", null);

        assertThrows(IllegalArgumentException.class, () -> runner(j).run(echo("BSL_TEST_VAR"), withNull));
    }

    @Test
    void doesNotEchoTheCommandIntoTheBuildLog(JenkinsRule j) {
        // The command this runner exists to execute is 'sts assume-role', whose arguments and output are
        // sensitive. quiet(true) is load-bearing, not cosmetic.
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Launcher launcher = j.jenkins.createLauncher(new hudson.util.StreamTaskListener(
                new PrintStream(log, true, StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        new LauncherProcessRunner(launcher).run(List.of("sh", "-c", "printf aws-build-session-logger-secret-marker"));

        assertFalse(
                log.toString(StandardCharsets.UTF_8).contains("aws-build-session-logger-secret-marker"),
                "the launcher must not echo the command into the listener");
    }

    private static LauncherProcessRunner runner(JenkinsRule j) {
        return new LauncherProcessRunner(j.jenkins.createLauncher(TaskListener.NULL));
    }

    /** Prints one environment variable, with no trailing newline. */
    private static List<String> echo(String variable) {
        return List.of("sh", "-c", "printf %s \"$" + variable + "\"");
    }
}
