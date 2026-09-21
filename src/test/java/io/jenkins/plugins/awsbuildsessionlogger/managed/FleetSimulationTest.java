package io.jenkins.plugins.awsbuildsessionlogger.managed;

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
import java.util.LinkedHashMap;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The whole fleet, as real builds: every agent configuration shape that exists in production, run
 * through both job types, with unprofiled attribution off and on.
 *
 * <p>{@link ProductionShapeFixturesTest} asserts the transform is correct as a pure function. This
 * asserts that a real Jenkins build, running a real shell command, actually receives the result — which
 * is a different claim, and the one that matters before an upgrade that cannot easily be repeated.
 *
 * <p>Shapes are the measured ones, with placeholder account IDs:
 *
 * <ul>
 *   <li><b>standard</b> — six of seven agents, byte-identical: a credential-less {@code [default]},
 *       five role-assuming profiles, one profile that assumes nothing.
 *   <li><b>minimal</b> — the seventh agent: one profile, <b>no {@code [default]}</b>.
 *   <li><b>controller</b> — a superset, and the only file that <b>ends with a blank line</b>, which
 *       once suppressed decoration entirely.
 * </ul>
 */
@WithJenkins
class FleetSimulationTest {

    @TempDir
    private Path tmp;

    private static final String SELF = "arn:aws:iam::111111111111:role/agent-instance-role";

    private static Map<String, String> shapes() {
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put(
                "standard",
                "[default]\noutput = json\nregion = us-east-1\n"
                        + "[profile non_prod]\nrole_arn = arn:aws:iam::222222222222:role/terraform-assume-role\n"
                        + "credential_source = Ec2InstanceMetadata\nregion = us-east-1\n"
                        + "[profile ops]\ncredential_source = Ec2InstanceMetadata\nregion = us-east-1\n"
                        + "[profile tenant_b_prod]\nrole_arn = arn:aws:iam::333333333333:role/tenant-b-terraform-role\n"
                        + "credential_source = Ec2InstanceMetadata\nregion = us-east-1\n");
        shapes.put(
                "minimal",
                "[profile website]\nrole_arn = arn:aws:iam::444444444444:role/terraform-assume-role\n"
                        + "credential_source = Ec2InstanceMetadata\nregion = us-east-1\n");
        shapes.put(
                "controller",
                "[default]\noutput = json\nregion = us-east-1\n\n"
                        + "[profile prod]\nrole_arn = arn:aws:iam::555555555555:role/terraform-assume-role\n"
                        + "credential_source = Ec2InstanceMetadata\nregion = us-east-1\n\n"
                        + "[profile dr]\nrole_arn = arn:aws:iam::555555555555:role/terraform-assume-role-dr\n"
                        + "credential_source = Ec2InstanceMetadata\nregion = us-east-2\n\n"
                        + "[profile 666666666666]\nrole_arn = arn:aws:iam::666666666666:role/SecurityAdminRole\n"
                        + "credential_source = Ec2InstanceMetadata\n\n");
        return shapes;
    }

    @AfterEach
    void clearOverride() {
        System.clearProperty("io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile");
    }

    @Test
    void everyShapeIsDecoratedForAPipelineBuild(JenkinsRule j) throws Exception {
        int n = 0;
        for (Map.Entry<String, String> shape : shapes().entrySet()) {
            for (boolean unprofiled : new boolean[] {false, true}) {
                String name = "pipe-" + shape.getKey() + (unprofiled ? "-unprofiled" : "") + "-" + (++n);
                givenNodeConfig(shape.getValue());
                configure(j, unprofiled ? SELF : null);

                WorkflowJob job = j.createProject(WorkflowJob.class, name);
                job.setDefinition(new CpsFlowDefinition("node { sh 'cat \"$AWS_CONFIG_FILE\"' }\n", true));
                WorkflowRun build = job.scheduleBuild2(0).get();

                j.assertBuildStatusSuccess(build);
                assertDecorated(JenkinsRule.getLog(build), shape.getKey(), name, unprofiled);
            }
        }
    }

    @Test
    void everyShapeIsDecoratedForAFreestyleBuild(JenkinsRule j) throws Exception {
        int n = 0;
        for (Map.Entry<String, String> shape : shapes().entrySet()) {
            for (boolean unprofiled : new boolean[] {false, true}) {
                String name = "free-" + shape.getKey() + (unprofiled ? "-unprofiled" : "") + "-" + (++n);
                givenNodeConfig(shape.getValue());
                configure(j, unprofiled ? SELF : null);

                FreeStyleProject project = j.createFreeStyleProject(name);
                project.getBuildersList().add(new Shell("cat \"$AWS_CONFIG_FILE\""));
                FreeStyleBuild build = project.scheduleBuild2(0).get();

                j.assertBuildStatusSuccess(build);
                assertDecorated(JenkinsRule.getLog(build), shape.getKey(), name, unprofiled);
            }
        }
    }

    /**
     * What every combination must satisfy: the build's own session name appears, the agent's role ARNs
     * survive untouched, and when unprofiled attribution is on there is a usable {@code [default]}.
     */
    private static void assertDecorated(String log, String shape, String job, boolean unprofiled) {
        String session = "role_session_name = jk-" + job + "-1";
        assertTrue(log.contains(session), shape + "/" + job + " should carry its session name:\n" + log);

        if ("minimal".equals(shape)) {
            assertTrue(
                    log.contains("role_arn = arn:aws:iam::444444444444:role/terraform-assume-role"),
                    "the agent's own role must survive:\n" + log);
        } else {
            assertTrue(log.contains("output = json"), "the agent's own keys must survive:\n" + log);
        }

        if (unprofiled) {
            assertTrue(log.contains("[default]"), shape + " must have a default section:\n" + log);
            assertTrue(log.contains("role_arn = " + SELF), shape + " must assume the agent's role:\n" + log);
        } else if (!"minimal".equals(shape)) {
            // Left exactly as the agent wrote it: no role, and therefore no session name of its own.
            int start = log.indexOf("[default]");
            String defaultSection = log.substring(start, log.indexOf('[', start + 1));
            assertTrue(!defaultSection.contains("role_arn"), "default must be untouched:\n" + defaultSection);
        }
    }

    private void givenNodeConfig(String content) throws IOException {
        Path file = tmp.resolve("node-aws-config-" + System.nanoTime());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        System.setProperty("io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile", file.toString());
    }

    private static void configure(JenkinsRule j, String unprofiledRoleArn) {
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
    }
}
