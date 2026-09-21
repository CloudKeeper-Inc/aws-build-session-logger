package io.jenkins.plugins.awsbuildsessionlogger.managed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The shapes production actually builds, each of which can fail while the plugin reports success.
 *
 * <p>Every other test in this package asserts that the plugin <em>contributes</em> correctly. This one
 * asserts the property that matters more: that a build which works without the plugin still works with
 * it. A lost session name is a gap in an audit; a lost credential binding, a configuration file pointing
 * at nothing, or a corrupt file is a failed deployment.
 *
 * <p>Each test here corresponds to a shape observed in real-world Jenkinsfiles.
 */
@WithJenkins
class ProductionFailureModesTest {

    @TempDir
    private Path tmp;

    private static final String NODE_CONFIG = "[profile non_prod]\n"
            + "role_arn = arn:aws:iam::222222222222:role/terraform-assume-role\n"
            + "credential_source = Ec2InstanceMetadata\n"
            + "region = us-east-1\n";

    @AfterEach
    void clearOverride() {
        System.clearProperty("io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile");
    }

    /**
     * The shape that first exposed the shadowing defect: a credential binding, then a working-directory change, then
     * a command that reads the binding.
     *
     * <p>Lookup for an {@code EnvironmentExpander} begins at the innermost context level — the one the
     * directory change created. That level has no expander, so a {@link
     * org.jenkinsci.plugins.workflow.steps.DynamicContext} that answers unconditionally is consulted
     * before the walk reaches the enclosing level holding the binding. The binding is never seen.
     */
    @Test
    void aCredentialBindingSurvivesANestedWorkingDirectory(JenkinsRule j) throws Exception {
        givenNodeConfig();
        configure(j);

        WorkflowJob job = j.createProject(WorkflowJob.class, "binding-then-dir");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  fakeBinding {\n"
                        + "    fakeDir('application/service') {\n"
                        + "      sh 'echo BINDING=[${REPO_USERNAME:-LOST}]'\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n",
                true));
        WorkflowRun build = job.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(
                log.contains("BINDING=[repo-user]"),
                "the enclosing block's environment must survive a nested working directory:\n" + log);
    }

    /** The same binding, with no nested block. This path already works, and must keep working. */
    @Test
    void aCredentialBindingSurvivesWithoutANestedBlock(JenkinsRule j) throws Exception {
        givenNodeConfig();
        configure(j);

        WorkflowJob job = j.createProject(WorkflowJob.class, "binding-only");
        job.setDefinition(
                new CpsFlowDefinition("node { fakeBinding { sh 'echo BINDING=[${REPO_USERNAME:-LOST}]' } }\n", true));
        WorkflowRun build = job.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(build);
        assertTrue(JenkinsRule.getLog(build).contains("BINDING=[repo-user]"), JenkinsRule.getLog(build));
    }

    /**
     * A job that manages its own AWS configuration must keep it.
     *
     * <p>The plugin decorates the <em>node's</em> default. A value the job set deliberately is not a
     * default, and silently replacing it would point the build at a different identity than its author
     * intended — a wrong-identity failure, which is worse than an unattributed one.
     */
    @Test
    void aJobsOwnAwsConfigFileIsNotOverwritten(JenkinsRule j) throws Exception {
        givenNodeConfig();
        configure(j);

        WorkflowJob job = j.createProject(WorkflowJob.class, "job-owned-config");
        job.setDefinition(new CpsFlowDefinition("node { fakeBinding { sh 'echo CFG=[$AWS_CONFIG_FILE]' } }\n", true));
        WorkflowRun build = job.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(build);
        assertTrue(
                JenkinsRule.getLog(build).contains("CFG=[/job/owned/config]"),
                "a value the job set explicitly must win over the plugin's default:\n" + JenkinsRule.getLog(build));
    }

    /**
     * The generated file belongs to the build, not to whatever directory a step happened to be in.
     *
     * <p>Anchoring it to the current working directory writes {@code aws-build-session-logger/} into the middle of a
     * checked-out source tree, and — when a step changes into a directory outside the workspace — leaves
     * a directory on the agent that no workspace cleanup will ever reclaim.
     */
    @Test
    void theConfigurationIsNotWrittenIntoTheSourceTree(JenkinsRule j) throws Exception {
        givenNodeConfig();
        configure(j);

        WorkflowJob job = j.createProject(WorkflowJob.class, "source-tree");
        job.setDefinition(new CpsFlowDefinition(
                "node { fakeDir('checkout/inner') { sh 'echo CFG=[$AWS_CONFIG_FILE]' } }\n", true));
        WorkflowRun build = job.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertFalse(
                log.contains("checkout/inner@tmp"),
                "the configuration must stay anchored to the build's workspace, not the current directory:\n" + log);
    }

    /**
     * A workspace cleaned in the middle of a build must not leave the environment pointing at a file
     * that no longer exists.
     *
     * <p>{@code cleanWs()} and {@code deleteDir()} mid-build are ordinary idioms. Because the prepared
     * state is memoized for the whole build, every later step keeps exporting a path to a deleted file.
     * An AWS SDK reads that as an empty configuration, so {@code --profile non_prod} fails with
     * <em>"The config profile could not be found"</em> — with nothing thrown and nothing logged.
     */
    @Test
    void theConfigurationSurvivesTheWorkspaceBeingCleanedMidBuild(JenkinsRule j) throws Exception {
        givenNodeConfig();
        configure(j);

        WorkflowJob job = j.createProject(WorkflowJob.class, "cleaned-mid-build");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  sh 'test -f \"$AWS_CONFIG_FILE\" && echo FIRST=OK'\n"
                        + "  sh 'rm -rf \"$(dirname \"$AWS_CONFIG_FILE\")\"'\n"
                        + "  sh 'test -f \"$AWS_CONFIG_FILE\" && echo SECOND=OK || echo SECOND=MISSING'\n"
                        + "}\n",
                true));
        WorkflowRun build = job.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("FIRST=OK"), log);
        assertTrue(
                log.contains("SECOND=OK"),
                "the configuration must be regenerated after the workspace is cleaned:\n" + log);
    }

    /**
     * Parallel branches share one workspace, so they race to prepare the same file.
     *
     * <p>Both may miss the memo, and both then write the same path at the same time. The content is
     * identical, so the window is narrow — but a branch reading a half-written file gets an unparseable
     * configuration and every AWS call in that branch fails.
     */
    @Test
    void parallelBranchesAllSeeAWellFormedConfiguration(JenkinsRule j) throws Exception {
        givenNodeConfig();
        configure(j);

        WorkflowJob job = j.createProject(WorkflowJob.class, "parallel-branches");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  parallel(\n"
                        + "    a: { sh 'grep -c role_session_name \"$AWS_CONFIG_FILE\" | sed \"s/^/A=/\"' },\n"
                        + "    b: { sh 'grep -c role_session_name \"$AWS_CONFIG_FILE\" | sed \"s/^/B=/\"' },\n"
                        + "    c: { sh 'grep -c role_session_name \"$AWS_CONFIG_FILE\" | sed \"s/^/C=/\"' },\n"
                        + "    d: { sh 'grep -c role_session_name \"$AWS_CONFIG_FILE\" | sed \"s/^/D=/\"' }\n"
                        + "  )\n"
                        + "}\n",
                true));
        WorkflowRun build = job.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        for (String branch : new String[] {"A", "B", "C", "D"}) {
            assertTrue(log.contains(branch + "=1"), "branch " + branch + " saw a malformed configuration:\n" + log);
        }
    }

    /**
     * Observe-only mode must be completely invisible to a build.
     *
     * <p>This is the safety net for the class of defect that once dropped a job's credential binding: a contribution
     * that succeeds and still takes something away, which no exception guard can catch. In this mode the
     * plugin does all of its real work and exports nothing, so the estate can be surveyed under real
     * traffic with no possibility of affecting anyone.
     */
    @Test
    void observeOnlyModeExportsNothingAtAll(JenkinsRule j) throws Exception {
        givenNodeConfig();
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        assertNotNull(configuration);
        configure(j);
        configuration.setObserveOnly(true);
        try {
            WorkflowJob job = j.createProject(WorkflowJob.class, "observe-only");
            job.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                            + "  fakeBinding {\n"
                            + "    fakeDir('application/service') {\n"
                            + "      sh 'echo CFG=[${AWS_CONFIG_FILE:-unset}] BINDING=[${REPO_USERNAME:-LOST}]'\n"
                            + "    }\n"
                            + "  }\n"
                            + "}\n",
                    true));
            WorkflowRun build = job.scheduleBuild2(0).get();

            j.assertBuildStatusSuccess(build);
            String log = JenkinsRule.getLog(build);
            // The build's own binding is untouched, and the plugin contributed nothing of its own.
            assertTrue(log.contains("BINDING=[repo-user]"), log);
            assertTrue(log.contains("CFG=[/job/owned/config]"), "nothing of the plugin's may leak in:\n" + log);
            // It still reports what it would have done, which is the entire point of the mode.
            assertTrue(log.contains("OBSERVE ONLY"), "the survey must still be reported:\n" + log);
        } finally {
            configuration.setObserveOnly(false);
        }
    }

    // --- helpers -------------------------------------------------------------

    private void givenNodeConfig() throws IOException {
        Path file = tmp.resolve("node-aws-config-" + System.nanoTime());
        Files.writeString(file, NODE_CONFIG, StandardCharsets.UTF_8);
        System.setProperty("io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile", file.toString());
    }

    private static void configure(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        assertNotNull(configuration);
        configuration.setJobNamePattern(null);
        configuration.setJobNameExcludePattern(null);
        configuration.setNodeLabelPattern(null);
        configuration.setUnprofiledRoleArn(null);
        configuration.setManagedAuthentication(true);
        // Enforcing, explicitly. observeOnly ships ON, and these tests exist to exercise the path that
        // actually exports — the only one that can affect a build. The observe-only test below opts back
        // in deliberately after calling this.
        configuration.setObserveOnly(false);
    }
}
