package io.jenkins.plugins.awsbuildsessionlogger.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import hudson.model.Result;
import hudson.slaves.WorkspaceList;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for Managed Authentication end to end.
 *
 * <p><b>Read the pipeline scripts, not only the assertions.</b> Every one is an ordinary Jenkinsfile:
 * no wrapper, no plugin step, no import, nothing naming this plugin. That is the feature, and
 * {@link #noPipelineHereNamesThePlugin} asserts it mechanically so it cannot rot.
 *
 * <p>The node's AWS configuration is simulated by pointing the built-in node's {@code AWS_CONFIG_FILE}
 * at a temporary file, which is exactly the discovery path a real agent takes.
 *
 * <p>POSIX-only: the pipelines use {@code /bin/sh}.
 */
@WithJenkins
@DisabledOnOs(OS.WINDOWS)
class ManagedAwsContextTest {

    private static final String NODE_CONFIG = "# the node's own configuration\n"
            + "[profile non_prod]\n"
            + "role_arn = arn:aws:iam::222222222222:role/terraform-assume-role\n"
            + "credential_source = Ec2InstanceMetadata\n"
            + "region = us-east-1\n"
            + "\n"
            + "[profile plain]\n"
            + "region = eu-west-2\n";

    @TempDir
    private Path tmp;

    // --- off means invisible ---------------------------------------------------

    /**
     * The property that makes upgrading a production controller acceptable: switched off, the plugin
     * must be indistinguishable from not being installed. Not "harmless" — silent.
     */
    @Test
    void disabledIsIndistinguishableFromNotInstalled(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, false, null);

        WorkflowJob job = j.createProject(WorkflowJob.class, "off");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        // Whatever the node's environment says is what the build sees. The plugin
                        // contributes nothing, so in this JVM - where neither is set - both stay empty.
                        + "  sh 'test -z \"$AWS_CONFIG_FILE\"'\n"
                        + "  sh 'test -z \"$AWS_BUILD_SESSION_NAME\"'\n"
                        + "}\n",
                true));
        WorkflowRun build = j.assertBuildStatusSuccess(job.scheduleBuild2(0));

        assertFalse(JenkinsRule.getLog(build).contains("[aws-build-session-logger]"), "a disabled plugin says nothing");
        assertFalse(generated(j, job).exists(), "a disabled plugin writes nothing");
        assertEquals(0, ManagedAwsContext.preparedCount(), "a disabled plugin remembers nothing");
    }

    @Test
    void aJobOutsideTheRolloutPatternIsUntouched(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, "uat/.*");

        WorkflowJob job = j.createProject(WorkflowJob.class, "elsewhere");
        job.setDefinition(new CpsFlowDefinition("node { sh 'true' }\n", true));
        WorkflowRun build = j.assertBuildStatusSuccess(job.scheduleBuild2(0));

        assertFalse(JenkinsRule.getLog(build).contains("[aws-build-session-logger]"));
        assertFalse(generated(j, job).exists());
    }

    // --- the contract: decorate, never replace ---------------------------------

    @Test
    void decoratesTheNodesOwnConfigurationWithTheSessionName(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);

        WorkflowRun build = run(j, "decorates", "node { sh 'cat \"$AWS_CONFIG_FILE\"' }");

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("role_session_name = jk-decorates-1"), log);
        // Everything the node said is still there, verbatim.
        assertTrue(log.contains("role_arn = arn:aws:iam::222222222222:role/terraform-assume-role"), log);
        assertTrue(log.contains("credential_source = Ec2InstanceMetadata"), log);
        assertTrue(log.contains("# the node's own configuration"), log);
        assertTrue(log.contains("[profile plain]"), log);
    }

    @Test
    void doesNotTouchTheSharedCredentialsFile(JenkinsRule j) throws Exception {
        // Measured: pointing AWS_SHARED_CREDENTIALS_FILE at a plugin-owned file breaks every profile
        // that chains through source_profile to a credentials-file profile. So it must stay untouched.
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);

        WorkflowRun build = run(j, "credsfile", "node { sh 'echo \"[$AWS_SHARED_CREDENTIALS_FILE]\"' }");

        j.assertBuildStatusSuccess(build);
        assertTrue(JenkinsRule.getLog(build).contains("[]"), JenkinsRule.getLog(build));
    }

    @Test
    void exportsNoCredentials(JenkinsRule j) throws Exception {
        // The plugin decorates configuration; it never becomes the authentication provider.
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);

        WorkflowRun build = run(
                j,
                "nocreds",
                "node {\n"
                        + "  sh 'test -z \"$AWS_ACCESS_KEY_ID\"'\n"
                        + "  sh 'test -z \"$AWS_SECRET_ACCESS_KEY\"'\n"
                        + "  sh 'test -z \"$AWS_SESSION_TOKEN\"'\n"
                        + "}\n");

        j.assertBuildStatusSuccess(build);
    }

    @Test
    void aProfileTheNodeAlreadyPinnedIsLeftAlone(JenkinsRule j) throws Exception {
        givenNodeConfig(j, "[profile pinned]\nrole_arn = arn:aws:iam::1:role/r\nrole_session_name = set-by-admin\n");
        configure(j, true, null);

        WorkflowRun build = run(j, "pinned", "node { sh 'cat \"$AWS_CONFIG_FILE\"' }");

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("role_session_name = set-by-admin"), log);
        // The plugin still announces the build's session name; what matters is that it did not write
        // it into a profile the administrator had already pinned.
        assertFalse(log.contains("role_session_name = jk-pinned-1"), log);
    }

    @Test
    void aProfileConfiguredOnlyInJenkinsIsAppended(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null, profile("sandbox", "arn:aws:iam::9:role/sandbox", "eu-west-1"));

        WorkflowRun build = run(j, "fallback", "node { sh 'cat \"$AWS_CONFIG_FILE\"' }");

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("[profile sandbox]"), log);
        assertTrue(log.contains("role_arn = arn:aws:iam::222222222222:role/terraform-assume-role"), log);
    }

    // --- fail safe: none of these may fail a build -----------------------------

    @Test
    void aNodeWithNoAwsConfigurationDoesNotFailTheBuild(JenkinsRule j) throws Exception {
        givenNodeConfigPath(j, tmp.resolve("does-not-exist").toString());
        configure(j, true, null);

        WorkflowRun build = run(j, "noconfig", "node { sh 'test -z \"$AWS_BUILD_SESSION_NAME\"' }");

        j.assertBuildStatusSuccess(build);
        assertTrue(JenkinsRule.getLog(build).contains("no AWS configuration found"), JenkinsRule.getLog(build));
    }

    @Test
    void anUnreadableConfigurationDoesNotFailTheBuild(JenkinsRule j) throws Exception {
        Path unreadable = tmp.resolve("unreadable-dir");
        Files.createDirectory(unreadable); // a directory where a file is expected
        givenNodeConfigPath(j, unreadable.toString());
        configure(j, true, null);

        j.assertBuildStatusSuccess(run(j, "unreadable", "node { sh 'true' }"));
    }

    @Test
    void aConfigurationOfGarbageDoesNotFailTheBuild(JenkinsRule j) throws Exception {
        givenNodeConfig(j, "  not an ini [[[ = = =\n\n[[[\n");
        configure(j, true, null);

        j.assertBuildStatusSuccess(run(j, "garbage", "node { sh 'true' }"));
    }

    @Test
    void aBuildUsingAnUnknownProfileFailsExactlyAsItWouldHaveAnyway(JenkinsRule j) throws Exception {
        // The plugin must not turn an already-broken command into a different kind of broken. The node
        // does not define 'nosuch', so it did not resolve before and does not resolve now.
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);

        WorkflowRun build = run(j, "unknown", "node { sh 'grep -q \"profile nosuch\" \"$AWS_CONFIG_FILE\"' }");

        j.assertBuildStatus(Result.FAILURE, build);
        // and the decoration itself succeeded - the failure is the pipeline's own grep
        assertTrue(JenkinsRule.getLog(build).contains("[aws-build-session-logger] AWS configuration decorated"));
    }

    // --- lifecycle -------------------------------------------------------------

    @Test
    void removesWhatItGeneratedWhenTheBuildFinishes(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);

        WorkflowJob job = j.createProject(WorkflowJob.class, "cleanup");
        job.setDefinition(new CpsFlowDefinition("node { sh 'test -s \"$AWS_CONFIG_FILE\"' }\n", true));
        j.assertBuildStatusSuccess(job.scheduleBuild2(0));

        assertFalse(generated(j, job).exists(), "the decorated copy must not outlive the build");
        assertEquals(
                0,
                ManagedAwsContext.preparedCount(),
                ManagedAwsContext.preparedKeys().toString());
    }

    @Test
    void removesWhatItGeneratedEvenWhenTheBuildFails(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);

        WorkflowJob job = j.createProject(WorkflowJob.class, "cleanup-failure");
        job.setDefinition(new CpsFlowDefinition("node { sh 'test -s \"$AWS_CONFIG_FILE\"'; error 'boom' }\n", true));
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        assertFalse(generated(j, job).exists());
        assertEquals(0, ManagedAwsContext.preparedCount());
    }

    @Test
    void decoratesOncePerNodeBlockNotOncePerStep(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);

        WorkflowRun build = run(j, "memo", "node {\n  sh 'true'\n  sh 'true'\n  sh 'true'\n  sh 'true'\n}\n");

        j.assertBuildStatusSuccess(build);
        assertEquals(
                1,
                JenkinsRule.getLog(build).split("AWS configuration decorated", -1).length - 1,
                "everything after the first step must be a memo hit with no I/O");
    }

    @Test
    void parallelBranchesShareOneSessionName(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);

        WorkflowRun build = run(
                j,
                "parallel-branches",
                "parallel a: { node { sh 'echo A=$AWS_BUILD_SESSION_NAME' } },\n"
                        + "         b: { node { sh 'echo B=$AWS_BUILD_SESSION_NAME' } }\n");

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("A=jk-parallel-branches-1"), log);
        assertTrue(log.contains("B=jk-parallel-branches-1"), log);
    }

    // --- the headline claim ----------------------------------------------------

    @Test
    void noPipelineHereNamesThePlugin(JenkinsRule j) throws Exception {
        String source = Files.readString(
                Path.of("src/test/java/io/jenkins/plugins/awsbuildsessionlogger/managed/ManagedAwsContextTest.java"),
                StandardCharsets.UTF_8);

        // Built from parts so this assertion does not match itself.
        String wrapperStep = "awsBuildSessionLogger" + "WithProfile(";
        assertFalse(
                source.contains(wrapperStep),
                "managed authentication must never require a pipeline to name the plugin");
    }

    // --- helpers ---------------------------------------------------------------

    // --- node scoping, and the controller ------------------------------------

    /**
     * The controller runs builds — it has executors, and jobs get pinned to it — so it must be
     * reachable by node scoping. It reports no labels of its own, which is why its node name is matched
     * as well; without that, a label pattern could never select it and its builds would silently fall
     * out of scope.
     */
    @Test
    void aBuildOnTheBuiltInNodeIsSelectableByNodeScope(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);
        AwsBuildSessionConfiguration.get().setNodeLabelPattern("built-in");

        WorkflowRun build = run(j, "controller-in-scope", "node { sh 'cat \"$AWS_CONFIG_FILE\"' }");

        j.assertBuildStatusSuccess(build);
        assertTrue(
                JenkinsRule.getLog(build).contains("role_session_name = jk-controller-in-scope-1"),
                JenkinsRule.getLog(build));
    }

    @Test
    void aNodeOutsideTheNodeScopeIsUntouched(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);
        AwsBuildSessionConfiguration.get().setNodeLabelPattern("some-other-agent");

        WorkflowRun build = run(j, "node-out-of-scope", "node { sh 'echo [${AWS_CONFIG_FILE:-unset}]' }");

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("[unset]"), "nothing may be exported for a node out of scope: " + log);
    }

    // --- attributing calls that name no profile ------------------------------

    @Test
    void anUnprofiledRoleMakesTheDefaultSectionAssumeARole(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);
        AwsBuildSessionConfiguration.get().setUnprofiledRoleArn("arn:aws:iam::111111111111:role/agent-instance-role");

        WorkflowRun build = run(j, "unprofiled", "node { sh 'cat \"$AWS_CONFIG_FILE\"' }");

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("[default]"), log);
        assertTrue(log.contains("role_arn = arn:aws:iam::111111111111:role/agent-instance-role"), log);
        assertTrue(log.contains("role_session_name = jk-unprofiled-1"), log);
        assertTrue(log.contains("calls naming no profile are attributed too"), log);
    }

    @Test
    void withoutAnUnprofiledRoleNothingIsInventedForTheDefaultSection(JenkinsRule j) throws Exception {
        givenNodeConfig(j, NODE_CONFIG);
        configure(j, true, null);

        WorkflowRun build = run(j, "no-unprofiled", "node { sh 'cat \"$AWS_CONFIG_FILE\"' }");

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertFalse(log.contains("[default]"), "a default section must never be conjured up: " + log);
    }

    private void givenNodeConfig(JenkinsRule j, String content) throws Exception {
        Path file = tmp.resolve("node-aws-config-" + System.nanoTime());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        givenNodeConfigPath(j, file.toString());
    }

    /**
     * Simulates the node's own AWS configuration. Computer#getEnvironment() reports the agent process's
     * real operating-system environment - correct for production, impossible to fake from inside the
     * same JVM - so tests use the documented path-override property instead.
     */
    private static void givenNodeConfigPath(JenkinsRule j, String path) {
        System.setProperty("io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile", path);
    }

    @org.junit.jupiter.api.AfterEach
    void clearNodeConfigOverride() {
        System.clearProperty("io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile");
    }

    private static FilePath generated(JenkinsRule j, WorkflowJob job) {
        FilePath workspace = j.jenkins.getWorkspaceFor(job);
        assertNotNull(workspace);
        FilePath temporary = WorkspaceList.tempDir(workspace);
        assertNotNull(temporary);
        return temporary.child(ManagedAwsContext.DIRECTORY_NAME);
    }

    private static WorkflowRun run(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun build = job.scheduleBuild2(0).get();
        assertNotNull(build);
        return build;
    }

    private static void configure(JenkinsRule j, boolean enabled, String pattern, AwsProfile... profiles) {
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        assertNotNull(configuration);
        configuration.setProfiles(List.of(profiles));
        configuration.setJobNamePattern(pattern);
        configuration.setManagedAuthentication(enabled);
        // Explicitly enforcing. observeOnly ships ON so that turning the master switch on for the
        // first time cannot change every build at once — but these tests exist to exercise the
        // ENFORCING path, the only one that can affect a build. Without this line they would all
        // silently run in observe-only and assert nothing about what is exported.
        configuration.setObserveOnly(false);
    }

    private static AwsProfile profile(String name, String roleArn, String region) {
        AwsProfile profile = new AwsProfile(name, roleArn);
        profile.setMode(AwsProfile.ASSUME_ROLE);
        profile.setRegion(region);
        return profile;
    }
}
