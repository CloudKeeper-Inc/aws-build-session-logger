package io.jenkins.plugins.awsbuildsessionlogger.managed;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.slaves.DumbSlave;
import hudson.tasks.Shell;
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
 * Every job type, on a real agent as well as on the controller.
 *
 * <p>Every other test in this package runs on the built-in node, where the agent's filesystem is the
 * controller's own and a {@link hudson.FilePath} is a local file. On a real agent it is a remote path
 * reached over a channel, and the configuration is written and read across it. That difference is the
 * whole point of an agent, and it had not been exercised.
 *
 * <p>The matrix asserted here is the one that matters in production: {Pipeline, Freestyle} ×
 * {controller, agent}, plus a raw {@code aws}-style command reading the environment it was given rather
 * than being told about the plugin.
 */
@WithJenkins
class AgentCoverageTest {

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

    // --- on a real agent -----------------------------------------------------

    @Test
    void aPipelineBuildOnAnAgentIsDecorated(JenkinsRule j) throws Exception {
        givenNodeConfig();
        DumbSlave agent = j.createOnlineSlave();
        configure(j, null);

        WorkflowJob job = j.createProject(WorkflowJob.class, "pipeline-on-agent");
        job.setDefinition(
                new CpsFlowDefinition("node('" + agent.getNodeName() + "') { sh 'cat \"$AWS_CONFIG_FILE\"' }\n", true));
        WorkflowRun build = job.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("role_session_name = jk-pipeline-on-agent-1"), log);
        assertTrue(log.contains("role_arn = arn:aws:iam::222222222222:role/terraform-assume-role"), log);
    }

    @Test
    void aFreestyleBuildOnAnAgentIsDecorated(JenkinsRule j) throws Exception {
        givenNodeConfig();
        DumbSlave agent = j.createOnlineSlave();
        configure(j, null);

        FreeStyleProject project = j.createFreeStyleProject("freestyle-on-agent");
        project.setAssignedNode(agent);
        project.getBuildersList().add(new Shell("cat \"$AWS_CONFIG_FILE\""));
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("role_session_name = jk-freestyle-on-agent-1"), log);
        assertTrue(
                ((Node) agent).getNodeName().equals(build.getBuiltOnStr()),
                "the build must actually have run on the agent, not the controller");
    }

    /**
     * The generated file must live on the <em>agent</em>. If the plugin wrote it on the controller and
     * exported that path, the agent would be pointed at a file it cannot see — which fails only on a
     * real agent and never on the built-in node.
     */
    @Test
    void theGeneratedConfigurationIsWrittenOnTheAgentNotTheController(JenkinsRule j) throws Exception {
        givenNodeConfig();
        DumbSlave agent = j.createOnlineSlave();
        configure(j, null);

        WorkflowJob job = j.createProject(WorkflowJob.class, "written-remotely");
        // `sh -x` echoes the expanded command, so the resolved path appears in the log without needing
        // an `echo` step, which this minimal test harness does not register.
        job.setDefinition(new CpsFlowDefinition(
                "node('" + agent.getNodeName() + "') {\n"
                        + "  sh 'test -f \"$AWS_CONFIG_FILE\" && echo FILE-EXISTS-ON-AGENT'\n"
                        + "}\n",
                true));
        WorkflowRun build = job.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(build);
        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("FILE-EXISTS-ON-AGENT"), log);
        assertTrue(log.contains(agent.getRemoteFS()), "the path must be under the agent's own root: " + log);
    }

    // --- a raw command, told nothing about the plugin ------------------------

    /**
     * The contract is that an unmodified command inherits the configuration through the environment.
     * This runs a script that knows nothing about Jenkins or the plugin and reads only what any AWS SDK
     * would read.
     */
    @Test
    void aRawCommandInheritsTheConfigurationThroughTheEnvironment(JenkinsRule j) throws Exception {
        givenNodeConfig();
        DumbSlave agent = j.createOnlineSlave();
        configure(j, null);

        WorkflowJob job = j.createProject(WorkflowJob.class, "raw-command");
        job.setDefinition(new CpsFlowDefinition(
                "node('" + agent.getNodeName() + "') {\n"
                        + "  sh '''\n"
                        + "    # exactly what an AWS SDK does: read the shared config file it is pointed at\n"
                        + "    grep -c role_session_name \"$AWS_CONFIG_FILE\" | sed 's/^/SESSION_NAMES=/'\n"
                        + "  '''\n"
                        + "}\n",
                true));
        WorkflowRun build = job.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(build);
        assertTrue(JenkinsRule.getLog(build).contains("SESSION_NAMES=1"), JenkinsRule.getLog(build));
    }

    // --- node scoping distinguishes controller from agent --------------------

    @Test
    void nodeScopingCanSelectTheAgentAndExcludeTheController(JenkinsRule j) throws Exception {
        givenNodeConfig();
        DumbSlave agent = j.createOnlineSlave();
        AwsBuildSessionConfiguration configuration = configure(j, null);
        configuration.setNodeLabelPattern(agent.getNodeName());

        WorkflowJob onAgent = j.createProject(WorkflowJob.class, "scoped-agent");
        onAgent.setDefinition(new CpsFlowDefinition(
                "node('" + agent.getNodeName() + "') { sh 'echo [${AWS_CONFIG_FILE:-unset}]' }\n", true));
        WorkflowRun agentBuild = onAgent.scheduleBuild2(0).get();

        WorkflowJob onController = j.createProject(WorkflowJob.class, "scoped-controller");
        onController.setDefinition(
                new CpsFlowDefinition("node('built-in') { sh 'echo [${AWS_CONFIG_FILE:-unset}]' }\n", true));
        WorkflowRun controllerBuild = onController.scheduleBuild2(0).get();

        j.assertBuildStatusSuccess(agentBuild);
        j.assertBuildStatusSuccess(controllerBuild);
        assertTrue(!JenkinsRule.getLog(agentBuild).contains("[unset]"), "the agent is in scope");
        assertTrue(JenkinsRule.getLog(controllerBuild).contains("[unset]"), "the controller is not");
    }

    // --- helpers -------------------------------------------------------------

    private void givenNodeConfig() throws IOException {
        Path file = tmp.resolve("node-aws-config-" + System.nanoTime());
        Files.writeString(file, NODE_CONFIG, StandardCharsets.UTF_8);
        System.setProperty("io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile", file.toString());
    }

    private static AwsBuildSessionConfiguration configure(JenkinsRule j, String unprofiledRoleArn) {
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        assertNotNull(configuration);
        configuration.setJobNamePattern(null);
        configuration.setJobNameExcludePattern(null);
        configuration.setNodeLabelPattern(null);
        configuration.setUnprofiledRoleArn(unprofiledRoleArn);
        configuration.setManagedAuthentication(true);
        // Explicitly enforcing. observeOnly ships ON so that turning the master switch on for the
        // first time cannot change every build at once — but these tests exist to exercise the
        // ENFORCING path, the only one that can affect a build. Without this line they would all
        // silently run in observe-only and assert nothing about what is exported.
        configuration.setObserveOnly(false);
        return configuration;
    }
}
