package io.jenkins.plugins.awsbuildsessionlogger.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Real end-to-end tests for {@link DefaultProcessRunner}, exercising actual process stream/exit
 * handling. Uses a POSIX shell, so it is skipped on Windows. No Jenkins, no AWS.
 */
@DisabledOnOs(OS.WINDOWS)
class DefaultProcessRunnerTest {

    private final DefaultProcessRunner runner = new DefaultProcessRunner();

    @Test
    void capturesStdoutStderrAndNonZeroExitSeparately() {
        ProcessResult result = runner.run(List.of("sh", "-c", "printf out; printf err 1>&2; exit 3"));

        assertEquals("out", result.stdout());
        assertEquals("err", result.stderr());
        assertEquals(3, result.exitCode());
        assertFalse(result.succeeded());
    }

    @Test
    void reportsSuccessOnZeroExit() {
        ProcessResult result = runner.run(List.of("sh", "-c", "printf hi"));

        assertEquals("hi", result.stdout());
        assertEquals("", result.stderr());
        assertTrue(result.succeeded());
    }

    @Test
    void throwsWhenExecutableDoesNotExist() {
        assertThrows(
                ProcessExecutionException.class,
                () -> runner.run(List.of("aws-build-session-logger-no-such-binary-42")));
    }

    @Test
    void rejectsEmptyCommand() {
        assertThrows(IllegalArgumentException.class, () -> runner.run(List.of()));
    }

    @Test
    void appliesEnvironmentOverridesToTheChild() {
        ProcessResult result = runner.run(echo("BSL_TEST_VAR"), Map.of("BSL_TEST_VAR", "overridden"));

        assertEquals("overridden", result.stdout());
        assertTrue(result.succeeded());
    }

    @Test
    void inheritedEnvironmentSurvivesAnUnrelatedOverride() {
        String inherited = anInheritedVariable();
        ProcessResult result = runner.run(echo(inherited), Map.of("BSL_TEST_VAR", "overridden"));

        assertEquals(System.getenv(inherited), result.stdout());
    }

    @Test
    void aNullOverrideValueRemovesTheVariableFromTheChild() {
        String inherited = anInheritedVariable();

        // Baseline first, so this cannot pass vacuously against a variable that was never there.
        assertEquals(System.getenv(inherited), runner.run(echo(inherited)).stdout());

        Map<String, String> removal = new HashMap<>();
        removal.put(inherited, null);
        assertEquals("", runner.run(echo(inherited), removal).stdout());
    }

    /** Prints the named variable's value, or nothing at all when it is unset. */
    private static List<String> echo(String variable) {
        return List.of("sh", "-c", "printf %s \"$" + variable + "\"");
    }

    /**
     * A non-empty variable this JVM inherited whose name is a plain shell identifier, other than
     * {@code PATH} — removing {@code PATH} would also break resolving {@code sh} itself, which would make
     * the removal test lie.
     */
    private static String anInheritedVariable() {
        String found = System.getenv().entrySet().stream()
                .filter(e -> !"PATH".equals(e.getKey()))
                .filter(e -> e.getKey().matches("[A-Za-z_][A-Za-z0-9_]*"))
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst()
                .orElse(null);
        assumeTrue(found != null, "needs at least one inherited environment variable besides PATH");
        return found;
    }
}
