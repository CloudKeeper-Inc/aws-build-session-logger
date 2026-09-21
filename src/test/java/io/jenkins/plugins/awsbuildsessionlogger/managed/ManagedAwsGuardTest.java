package io.jenkins.plugins.awsbuildsessionlogger.managed;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Map;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.junit.jupiter.api.Test;

/**
 * Tests for the outermost guard around the whole contribution path.
 *
 * <p>This is the last line of the plugin's central promise: <b>losing attribution is acceptable,
 * failing a deployment is not.</b> Anything escaping the contribution path propagates through
 * {@code ContextVariableSet} into the step and fails the build, so the guard is tested directly rather
 * than only through the scenarios that happen to be reachable from a pipeline.
 *
 * <p>Plain JUnit: the guard touches no Jenkins state, and the states it exists to survive are ones a
 * {@code JenkinsRule} cannot easily produce.
 */
class ManagedAwsGuardTest {

    @Test
    void aSuccessfulAttemptIsReturnedUnchanged() throws Exception {
        EnvironmentExpander expander = EnvironmentExpander.constant(Map.of("A", "b"));

        assertSame(expander, ManagedAwsContext.guarded(() -> expander));
    }

    @Test
    void contributingNothingIsAllowed() throws Exception {
        assertNull(ManagedAwsContext.guarded(() -> null));
    }

    @Test
    void aCheckedExceptionContributesNothingRatherThanFailingTheBuild() throws Exception {
        // DelegatedContext.get is declared to throw IOException; before the guard covered the whole
        // path, that propagated into the step.
        assertNull(ManagedAwsContext.guarded(() -> {
            throw new IOException("unreadable build record");
        }));
    }

    @Test
    void aRuntimeExceptionContributesNothing() throws Exception {
        assertNull(ManagedAwsContext.guarded(() -> {
            throw new IllegalStateException("Jenkins is shutting down");
        }));
    }

    @Test
    void anErrorContributesNothing() throws Exception {
        // The case that motivated catching Throwable rather than Exception: a classloading failure
        // after an upgrade would otherwise break every step on the controller, not one build.
        assertNull(ManagedAwsContext.guarded(() -> {
            throw new NoClassDefFoundError("io/jenkins/plugins/awsbuildsessionlogger/Missing");
        }));
        assertNull(ManagedAwsContext.guarded(() -> {
            throw new StackOverflowError();
        }));
    }

    @Test
    void anInterruptionStillPropagatesSoAbortsAreNotSwallowed() {
        // A build being aborted must stay aborted. Swallowing this would make the plugin the reason a
        // cancelled deployment kept running.
        InterruptedException thrown = assertThrows(
                InterruptedException.class,
                () -> ManagedAwsContext.guarded(() -> {
                    throw new InterruptedException("build aborted");
                }));

        assertNotNull(thrown);
    }
}
