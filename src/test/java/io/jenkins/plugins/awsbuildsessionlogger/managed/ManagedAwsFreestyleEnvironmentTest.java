package io.jenkins.plugins.awsbuildsessionlogger.managed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.tasks.Shell;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Managed Authentication on Freestyle builds.
 *
 * <p>Real Freestyle projects running a real shell step, so what is asserted is what a build actually
 * receives — not what a unit test believes it would. Freestyle jobs on this controller upload to S3 and
 * back up Route 53, so "the plugin only covers Pipeline" was a hole in the audit rather than a
 * limitation worth documenting.
 *
 * <p>The behaviour asserted is deliberately identical to the Pipeline path, because both go through the
 * same {@link ManagedAwsContext#prepareVariables}.
 */
@WithJenkins
class ManagedAwsFreestyleEnvironmentTest {

    @TempDir
    private Path tmp;

    private static final String NODE_CONFIG = "# the node's own configuration\n"
            + "[profile non_prod]\n"
            + "role_arn = arn:aws:iam::222222222222:role/terraform-assume-role\n"
            + "credential_source = Ec2InstanceMetadata\n"
            + "region = us-east-1\n"
            + "[profile plain]\n"
            + "region = us-east-1\n";

    @AfterEach
    void clearOverride() {
        System.clearProperty("io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile");
    }

    @Test
    void aFreestyleBuildGetsTheDecoratedConfiguration(JenkinsRule j) throws Exception {
        givenNodeConfig(NODE_CONFIG);
        configure(j, true, null, null);

        FreeStyleBuild build = run(j, "freestyle-decorates", "cat \"$AWS_CONFIG_FILE\"");

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("role_session_name = jk-freestyle-decorates-1"), log);
        assertTrue(log.contains("role_arn = arn:aws:iam::222222222222:role/terraform-assume-role"), log);
        assertTrue(log.contains("# the node's own configuration"), log);
    }

    @Test
    void aFreestyleBuildExportsTheSessionName(JenkinsRule j) throws Exception {
        givenNodeConfig(NODE_CONFIG);
        configure(j, true, null, null);

        FreeStyleBuild build = run(j, "freestyle-session", "echo \"[$AWS_BUILD_SESSION_NAME]\"");

        j.assertBuildStatusSuccess(build);
        assertTrue(JenkinsRule.getLog(build).contains("[jk-freestyle-session-1]"), JenkinsRule.getLog(build));
    }

    // --- the safety properties, which matter more than the feature ------------

    @Test
    void disabledIsIndistinguishableFromNotInstalled(JenkinsRule j) throws Exception {
        givenNodeConfig(NODE_CONFIG);
        configure(j, false, null, null);

        FreeStyleBuild build = run(j, "freestyle-off", "echo \"[${AWS_CONFIG_FILE:-unset}]\"");

        j.assertBuildStatusSuccess(build);
        assertTrue(JenkinsRule.getLog(build).contains("[unset]"), JenkinsRule.getLog(build));
    }

    @Test
    void aFreestyleJobOutsideTheRolloutPatternIsUntouched(JenkinsRule j) throws Exception {
        givenNodeConfig(NODE_CONFIG);
        configure(j, true, "^somethingelse$", null);

        FreeStyleBuild build = run(j, "freestyle-out-of-scope", "echo \"[${AWS_CONFIG_FILE:-unset}]\"");

        j.assertBuildStatusSuccess(build);
        assertTrue(JenkinsRule.getLog(build).contains("[unset]"), JenkinsRule.getLog(build));
    }

    @Test
    void anExcludedFreestyleJobIsUntouched(JenkinsRule j) throws Exception {
        givenNodeConfig(NODE_CONFIG);
        AwsBuildSessionConfiguration configuration = configure(j, true, null, null);
        configuration.setJobNameExcludePattern("freestyle-excluded");

        FreeStyleBuild build = run(j, "freestyle-excluded", "echo \"[${AWS_CONFIG_FILE:-unset}]\"");

        j.assertBuildStatusSuccess(build);
        assertTrue(JenkinsRule.getLog(build).contains("[unset]"), JenkinsRule.getLog(build));
    }

    /** A missing node configuration must not fail a build that works today. */
    @Test
    void aNodeWithNoAwsConfigurationDoesNotFailTheBuild(JenkinsRule j) throws Exception {
        System.setProperty(
                "io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile",
                tmp.resolve("absent").toString());
        configure(j, true, null, null);

        FreeStyleBuild build = run(j, "freestyle-no-config", "echo alive");

        j.assertBuildStatusSuccess(build);
        assertTrue(JenkinsRule.getLog(build).contains("alive"));
    }

    @Test
    void anUnreadableConfigurationDoesNotFailTheBuild(JenkinsRule j) throws Exception {
        Path directory = Files.createDirectory(tmp.resolve("a-directory-not-a-file"));
        System.setProperty("io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile", directory.toString());
        configure(j, true, null, null);

        FreeStyleBuild build = run(j, "freestyle-unreadable", "echo alive");

        j.assertBuildStatusSuccess(build);
        assertTrue(JenkinsRule.getLog(build).contains("alive"));
    }

    // --- unprofiled attribution, as for Pipeline ------------------------------

    @Test
    void aFreestyleBuildAlsoAttributesUnprofiledCalls(JenkinsRule j) throws Exception {
        givenNodeConfig(NODE_CONFIG);
        configure(j, true, null, "arn:aws:iam::111111111111:role/agent-instance-role");

        FreeStyleBuild build = run(j, "freestyle-unprofiled", "cat \"$AWS_CONFIG_FILE\"");

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("[default]"), log);
        assertTrue(log.contains("role_arn = arn:aws:iam::111111111111:role/agent-instance-role"), log);
        // The no-role named profile is attributed too, exactly as on the Pipeline path.
        String plain = log.substring(log.indexOf("[profile plain]"));
        assertTrue(plain.contains("role_session_name = jk-freestyle-unprofiled-1"), plain);
    }

    // --- cleanup shares the Pipeline implementation ---------------------------

    @Test
    void removesWhatItGeneratedWhenTheBuildFinishes(JenkinsRule j) throws Exception {
        givenNodeConfig(NODE_CONFIG);
        configure(j, true, null, null);

        FreeStyleProject project = j.createFreeStyleProject("freestyle-cleanup");
        project.getBuildersList().add(new Shell("test -s \"$AWS_CONFIG_FILE\""));
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(build);
        hudson.FilePath workspace = j.jenkins.getWorkspaceFor(project);
        assertNotNull(workspace);
        hudson.FilePath temporary = hudson.slaves.WorkspaceList.tempDir(workspace);
        assertNotNull(temporary);
        assertFalse(
                temporary.child(ManagedAwsContext.DIRECTORY_NAME).exists(),
                "the generated directory must not outlive the build");
    }

    // --- helpers -------------------------------------------------------------

    private void givenNodeConfig(String content) throws IOException {
        Path file = tmp.resolve("node-aws-config-" + System.nanoTime());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        System.setProperty("io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile", file.toString());
    }

    private static AwsBuildSessionConfiguration configure(
            JenkinsRule j, boolean enabled, String pattern, String unprofiledRoleArn) {
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        assertNotNull(configuration);
        configuration.setJobNamePattern(pattern);
        configuration.setJobNameExcludePattern(null);
        configuration.setUnprofiledRoleArn(unprofiledRoleArn);
        configuration.setManagedAuthentication(enabled);
        // Explicitly enforcing. observeOnly ships ON so that turning the master switch on for the
        // first time cannot change every build at once — but these tests exist to exercise the
        // ENFORCING path, the only one that can affect a build. Without this line they would all
        // silently run in observe-only and assert nothing about what is exported.
        configuration.setObserveOnly(false);
        return configuration;
    }

    private static FreeStyleBuild run(JenkinsRule j, String name, String script) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject(name);
        project.getBuildersList().add(new Shell(script));
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        assertNotNull(build);
        return build;
    }
}
