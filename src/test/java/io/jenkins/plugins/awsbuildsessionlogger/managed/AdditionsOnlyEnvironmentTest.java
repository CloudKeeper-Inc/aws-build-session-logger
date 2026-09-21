package io.jenkins.plugins.awsbuildsessionlogger.managed;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.EnvVars;
import java.util.Map;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.junit.jupiter.api.Test;

/**
 * The runtime additions-only invariant for the ENVIRONMENT surface.
 *
 * <p>The config-file surface has a runtime check ({@link AwsConfigOverlay#validate}).
 * The environment had none - it relied on merge ordering being correct by construction - and that
 * asymmetry is how the shadowing defect shipped: the exception guard cannot catch a contribution that
 * succeeds and still takes something away.
 *
 * <p>These tests assert the check directly rather than through a pipeline, because the point of the
 * invariant is to catch nesting shapes nobody has written yet - which by definition cannot be
 * reproduced by writing one.
 */
class AdditionsOnlyEnvironmentTest {

    private static EnvironmentExpander constant(Map<String, String> values) {
        return EnvironmentExpander.constant(values);
    }

    @Test
    void additiveContributionIsAccepted() throws Exception {
        EnvironmentExpander existing = constant(Map.of("REPO_USER", "svc", "REPO_PASS", "secret"));
        EnvironmentExpander ours = constant(Map.of("AWS_CONFIG_FILE", "/ws@tmp/aws-build-session-logger/config"));
        EnvironmentExpander merged = EnvironmentExpander.merge(ours, existing);

        assertNull(
                ManagedAwsContext.wouldRemoveSomething(existing, merged),
                "adding AWS_CONFIG_FILE alongside the enclosing block's variables removes nothing");
    }

    /**
     * The shadowing defect, reduced to its essence: the plugin answers with only its own variables, so the
     * enclosing {@code withCredentials} bindings never reach the step. Before this invariant existed,
     * nothing threw and nothing was logged - the Gradle build simply fell through to a public mirror.
     */
    @Test
    void droppingAnEnclosingVariableIsRejected() throws Exception {
        EnvironmentExpander existing = constant(Map.of("REPO_USER", "svc", "REPO_PASS", "secret"));
        EnvironmentExpander shadowing = constant(Map.of("AWS_CONFIG_FILE", "/ws@tmp/aws-build-session-logger/config"));

        String loss = ManagedAwsContext.wouldRemoveSomething(existing, shadowing);

        assertNotNull(loss, "shadowing the enclosing environment must be detected");
        assertTrue(loss.contains("REPO_USER") || loss.contains("REPO_PASS"), "names the dropped variable: " + loss);
        assertTrue(loss.contains("drop"), "says what would happen: " + loss);
    }

    @Test
    void changingAnEnclosingVariableIsRejected() throws Exception {
        EnvironmentExpander existing = constant(Map.of("AWS_REGION", "ap-south-1"));
        EnvironmentExpander clobbering = constant(Map.of("AWS_REGION", "us-east-1"));

        String loss = ManagedAwsContext.wouldRemoveSomething(existing, clobbering);

        assertNotNull(loss, "overwriting a variable the build set must be detected");
        assertTrue(loss.contains("AWS_REGION"), "names the variable: " + loss);
        assertTrue(loss.contains("change"), "says what would happen: " + loss);
    }

    /**
     * A job that sets its own {@code AWS_CONFIG_FILE} keeps it. This is the case the invariant must
     * NOT flag: {@code merge(ours, existing)} expands ours first, so the job's value is the one that
     * survives - which is the intended behaviour, because a value a job chose is not a default.
     */
    @Test
    void jobsOwnConfigFileWinsAndIsNotFlagged() throws Exception {
        EnvironmentExpander existing = constant(Map.of("AWS_CONFIG_FILE", "/job/own/config"));
        EnvironmentExpander ours = constant(Map.of("AWS_CONFIG_FILE", "/ws@tmp/aws-build-session-logger/config"));
        EnvironmentExpander merged = EnvironmentExpander.merge(ours, existing);

        assertNull(ManagedAwsContext.wouldRemoveSomething(existing, merged), "the job's own value survives the merge");

        EnvVars resolved = new EnvVars();
        merged.expand(resolved);
        assertTrue("/job/own/config".equals(resolved.get("AWS_CONFIG_FILE")), "the job's value is the one exported");
    }

    /** An enclosing block that sets nothing cannot lose anything. */
    @Test
    void emptyEnclosingEnvironmentIsAccepted() throws Exception {
        EnvironmentExpander existing = constant(Map.of());
        EnvironmentExpander ours = constant(Map.of("AWS_CONFIG_FILE", "/ws@tmp/aws-build-session-logger/config"));

        assertNull(ManagedAwsContext.wouldRemoveSomething(existing, EnvironmentExpander.merge(ours, existing)));
    }
}
