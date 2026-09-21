package io.jenkins.plugins.awsbuildsessionlogger.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Result;
import hudson.util.FormValidation;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for the block-scoped authentication wrapper.
 *
 * <p>Every test drives the real path Step -&gt; profile resolution -&gt; AuthCore -&gt; CliStsAssumeRole
 * -&gt; LauncherProcessRunner -&gt; a real subprocess, with a stub {@code aws} script standing in for the
 * AWS CLI. No AWS account, no credentials and no installed AWS CLI are needed.
 *
 * <p>POSIX-only (the stubs are {@code /bin/sh} scripts), matching the sibling step test.
 */
@WithJenkins
@DisabledOnOs(OS.WINDOWS)
class WithAwsBuildRoleStepTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/non_prod";

    private static final String ACCESS_KEY = "ASIAEXAMPLE";
    private static final String SECRET_KEY = "SECRETEXAMPLEVALUE";
    private static final String SESSION_TOKEN = "SESSIONTOKENVALUE";

    /** One tab-separated line, exactly as {@code --query Credentials.[...] --output text} produces. */
    private static final String CREDENTIAL_LINE =
            ACCESS_KEY + "\\t" + SECRET_KEY + "\\t" + SESSION_TOKEN + "\\t2026-07-24T13:00:00+00:00\\n";

    @TempDir
    private Path tmp;

    @AfterEach
    void clearExecutableOverride() {
        System.clearProperty(WithAwsBuildRoleStep.AWS_EXECUTABLE_PROPERTY);
    }

    // --- registration ---------------------------------------------------------

    @Test
    void stepIsRegisteredAsABlockStep(JenkinsRule j) {
        StepDescriptor descriptor = StepDescriptor.byFunctionName("withAwsBuildRole");

        assertNotNull(descriptor, "withAwsBuildRole step should be registered");
        assertInstanceOf(WithAwsBuildRoleStep.DescriptorImpl.class, descriptor);
        assertTrue(descriptor.takesImplicitBlockArgument(), "the step must wrap a body");
    }

    @Test
    void requiresAgentContextSoItCannotRunOnTheController(JenkinsRule j) {
        StepDescriptor descriptor = StepDescriptor.byFunctionName("withAwsBuildRole");
        assertNotNull(descriptor);

        // Launcher and FilePath only exist inside a 'node' block. Requiring them is how the step
        // guarantees the AssumeRole happens on the machine whose identity is being chained from.
        assertTrue(descriptor.getRequiredContext().contains(Launcher.class), "Launcher must be required");
        assertTrue(descriptor.getRequiredContext().contains(FilePath.class), "FilePath must be required");
    }

    @Test
    void failsOutsideANodeBlock(JenkinsRule j) throws Exception {
        configureProfile("non_prod", ROLE, null);
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "nonode");
        job.setDefinition(new CpsFlowDefinition("withAwsBuildRole('non_prod') { }\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        String log = JenkinsRule.getLog(build);

        assertTrue(
                log.contains("Launcher") || log.contains("node") || log.contains("context"),
                "should explain that agent context is missing, got:\n" + log);
    }

    // --- the contract: credentials reach the block ---------------------------

    @Test
    void exportsCredentialsIntoTheBlock(JenkinsRule j) throws Exception {
        configureProfile("non_prod", ROLE, null);
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "exports");
        // Asserts with plain Groovy: a mismatch fails the build, so a green build is the assertion.
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withAwsBuildRole('non_prod') {\n"
                        + "    assert env.AWS_ACCESS_KEY_ID == '" + ACCESS_KEY + "'\n"
                        + "    assert env.AWS_SECRET_ACCESS_KEY == '" + SECRET_KEY + "'\n"
                        + "    assert env.AWS_SESSION_TOKEN == '" + SESSION_TOKEN + "'\n"
                        + "    assert env.AWS_BUILD_SESSION_NAME == 'jk-exports-1'\n"
                        + "  }\n"
                        + "}\n",
                true));

        WorkflowRun build = j.buildAndAssertSuccess(job);
        j.assertLogContains("[aws-build-session-logger] Assuming role " + ROLE + " as session jk-exports-1", build);
        j.assertLogContains("[aws-build-session-logger] Released credentials for session jk-exports-1", build);
    }

    @Test
    void credentialsAreScopedToTheBlockAndGoneAfterIt(JenkinsRule j) throws Exception {
        // The entire justification for a block over a returned value: the scope actually ends.
        configureProfile("non_prod", ROLE, null);
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "scoped");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withAwsBuildRole('non_prod') {\n"
                        + "    assert env.AWS_SESSION_TOKEN == '" + SESSION_TOKEN + "'\n"
                        + "  }\n"
                        + "  assert env.AWS_SESSION_TOKEN == null\n"
                        + "  assert env.AWS_ACCESS_KEY_ID == null\n"
                        + "  assert env.AWS_BUILD_SESSION_NAME == null\n"
                        + "}\n",
                true));

        j.buildAndAssertSuccess(job);
    }

    @Test
    void credentialsReachRealSubprocessesInsideTheBlock(JenkinsRule j) throws Exception {
        // Environment export is only useful if a child process actually inherits it - that is the whole
        // integration contract for the AWS CLI, Terraform, boto3 and docker login alike.
        configureProfile("non_prod", ROLE, null);
        useStub(successStub());
        Path captured = tmp.resolve("child-env.txt");

        WorkflowJob job = j.createProject(WorkflowJob.class, "subprocess");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withAwsBuildRole('non_prod') {\n"
                        + "    sh 'printf %s \"$AWS_SESSION_TOKEN\" > " + captured + "'\n"
                        + "  }\n"
                        + "}\n",
                true));

        j.buildAndAssertSuccess(job);

        assertEquals(SESSION_TOKEN, Files.readString(captured, StandardCharsets.UTF_8));
    }

    @Test
    void masksCredentialsInTheConsole(JenkinsRule j) throws Exception {
        configureProfile("non_prod", ROLE, null);
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "masked");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withAwsBuildRole('non_prod') {\n"
                        + "    sh 'echo \"leaked: $AWS_SECRET_ACCESS_KEY $AWS_SESSION_TOKEN $AWS_ACCESS_KEY_ID\"'\n"
                        + "  }\n"
                        + "}\n",
                true));

        WorkflowRun build = j.buildAndAssertSuccess(job);
        String log = JenkinsRule.getLog(build);

        assertTrue(log.contains("leaked:"), "the command should have run, got:\n" + log);
        assertFalse(log.contains(SECRET_KEY), "secret access key must be masked");
        assertFalse(log.contains(SESSION_TOKEN), "session token must be masked");
        assertFalse(log.contains(ACCESS_KEY), "access key id must be masked");
    }

    @Test
    void theSessionNameReachesTheAssumeRoleCall(JenkinsRule j) throws Exception {
        // The load-bearing assertion for the whole project: CloudTrail attribution depends on this
        // exact argument, and a future IAM trust policy will deny anything that does not match jk-*.
        configureProfile("non_prod", ROLE, null);
        Path args = tmp.resolve("argv.txt");
        useStub(argvRecordingStub(args));

        WorkflowJob job = j.createProject(WorkflowJob.class, "paramflow");
        job.setDefinition(new CpsFlowDefinition("node { withAwsBuildRole('non_prod') { } }\n", true));
        j.buildAndAssertSuccess(job);

        List<String> argv = Files.readAllLines(args, StandardCharsets.UTF_8);
        assertEquals("sts", argv.get(0));
        assertEquals("assume-role", argv.get(1));
        assertEquals(ROLE, argv.get(argv.indexOf("--role-arn") + 1), "the resolved ARN should reach the CLI");
        assertEquals(
                "jk-paramflow-1",
                argv.get(argv.indexOf("--role-session-name") + 1),
                "job name and build number should reach the CLI as the session name");
    }

    // --- region ---------------------------------------------------------------

    @Test
    void exportsRegionFromTheProfileWhenConfigured(JenkinsRule j) throws Exception {
        configureProfile("non_prod", ROLE, "us-east-1");
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "withregion");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withAwsBuildRole('non_prod') {\n"
                        + "    assert env.AWS_REGION == 'us-east-1'\n"
                        + "    assert env.AWS_DEFAULT_REGION == 'us-east-1'\n"
                        + "  }\n"
                        + "}\n",
                true));

        j.buildAndAssertSuccess(job);
    }

    @Test
    void aStepLevelRegionOverridesTheProfile(JenkinsRule j) throws Exception {
        // Region is not a constant across an estate (DR in another region, Terraform elsewhere again),
        // so it must always be overridable at the call site.
        configureProfile("non_prod", ROLE, "us-east-1");
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "overrideregion");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withAwsBuildRole(profile: 'non_prod', region: 'us-east-2') {\n"
                        + "    assert env.AWS_REGION == 'us-east-2'\n"
                        + "  }\n"
                        + "}\n",
                true));

        j.buildAndAssertSuccess(job);
    }

    @Test
    void noRegionIsExportedWhenNoneIsConfigured(JenkinsRule j) throws Exception {
        // Exporting AWS_REGION="" would be worse than exporting nothing: it overrides whatever the agent
        // would otherwise resolve.
        configureProfile("non_prod", ROLE, null);
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "noregion");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withAwsBuildRole('non_prod') {\n"
                        + "    assert env.AWS_REGION == null\n"
                        + "  }\n"
                        + "}\n",
                true));

        j.buildAndAssertSuccess(job);
    }

    // --- fail closed ----------------------------------------------------------

    @Test
    void unknownProfileFailsClosedAndListsWhatIsConfigured(JenkinsRule j) throws Exception {
        configureProfile("non_prod", ROLE, null);
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "unknown");
        job.setDefinition(new CpsFlowDefinition("node { withAwsBuildRole('typo') { } }\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        String log = JenkinsRule.getLog(build);

        assertTrue(log.contains("typo"), "should name the profile that was asked for");
        assertTrue(log.contains("non_prod"), "should list the profiles that are configured");
        assertFalse(
                log.contains("\tat io.jenkins.plugins.awsbuildsessionlogger"), "should not print a Java stack trace");
    }

    @Test
    void noProfileAndNoRoleArnFailsClosed(JenkinsRule j) throws Exception {
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "neither");
        job.setDefinition(new CpsFlowDefinition("node { withAwsBuildRole(null) { } }\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.assertLogContains("requires a profile name", build);
    }

    @Test
    void bothProfileAndRoleArnIsRejectedAsAmbiguous(JenkinsRule j) throws Exception {
        configureProfile("non_prod", ROLE, null);
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "ambiguous");
        job.setDefinition(new CpsFlowDefinition(
                "node { withAwsBuildRole(profile: 'non_prod', roleArn: '" + ROLE + "') { } }\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.assertLogContains("either 'profile' or 'roleArn', not both", build);
    }

    @Test
    void assumeRoleFailureAbortsWithAnActionableMessage(JenkinsRule j) throws Exception {
        configureProfile("non_prod", ROLE, null);
        useStub(failingStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "denied");
        job.setDefinition(new CpsFlowDefinition("node { withAwsBuildRole('non_prod') { } }\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        String log = JenkinsRule.getLog(build);

        assertTrue(log.contains(ROLE), "should name the role");
        assertTrue(log.contains("jk-denied-1"), "should name the session");
        assertTrue(log.contains("AccessDenied"), "should surface stderr");
        assertFalse(
                log.contains("\tat io.jenkins.plugins.awsbuildsessionlogger"), "should not print a Java stack trace");
    }

    @Test
    void theBodyDoesNotRunWhenAuthenticationFails(JenkinsRule j) throws Exception {
        // Fail closed means the block never opens - not that it opens without credentials.
        configureProfile("non_prod", ROLE, null);
        useStub(failingStub());
        Path marker = tmp.resolve("body-ran.txt");

        WorkflowJob job = j.createProject(WorkflowJob.class, "bodyskipped");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n" + "  withAwsBuildRole('non_prod') {\n" + "    sh 'touch " + marker + "'\n" + "  }\n" + "}\n",
                true));

        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        assertFalse(Files.exists(marker), "the body must not execute when authentication failed");
    }

    @Test
    void aFailureInsideTheBlockStillFailsTheBuild(JenkinsRule j) throws Exception {
        configureProfile("non_prod", ROLE, null);
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "bodyfails");
        job.setDefinition(new CpsFlowDefinition("node { withAwsBuildRole('non_prod') { sh 'exit 7' } }\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        // The scope still closes cleanly on the failure path.
        j.assertLogContains("[aws-build-session-logger] Released credentials for session jk-bodyfails-1", build);
    }

    // --- the roleArn escape hatch --------------------------------------------

    @Test
    void anExplicitRoleArnBypassesTheMapping(JenkinsRule j) throws Exception {
        // For pipelines whose profile is not in JCasC yet. Documented as not a security boundary.
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "explicitarn");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withAwsBuildRole(roleArn: '" + ROLE + "') {\n"
                        + "    assert env.AWS_SESSION_TOKEN == '" + SESSION_TOKEN + "'\n"
                        + "  }\n"
                        + "}\n",
                true));

        WorkflowRun build = j.buildAndAssertSuccess(job);
        j.assertLogContains("[aws-build-session-logger] Assuming role " + ROLE + " as session jk-explicitarn-1", build);
    }

    // --- form validation ------------------------------------------------------

    @Test
    void profileFormValidation(JenkinsRule j) {
        configureProfile("non_prod", ROLE, null);
        WithAwsBuildRoleStep.DescriptorImpl descriptor =
                (WithAwsBuildRoleStep.DescriptorImpl) StepDescriptor.byFunctionName("withAwsBuildRole");
        assertNotNull(descriptor);

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckProfile("non_prod").kind);
        // A warning, not an error: the roleArn escape hatch is a legitimate reason to leave this blank,
        // and a profile may be added to JCasC after the pipeline is written.
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckProfile("").kind);
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckProfile("nope").kind);
    }

    // --- helpers --------------------------------------------------------------

    private static void configureProfile(String name, String roleArn, String region) {
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        assertNotNull(configuration);
        AwsProfile profile = new AwsProfile(name, roleArn);
        profile.setRegion(region);
        configuration.setProfiles(List.of(profile));
    }

    private void useStub(Path stub) {
        System.setProperty(WithAwsBuildRoleStep.AWS_EXECUTABLE_PROPERTY, stub.toString());
    }

    private Path successStub() throws IOException {
        return writeScript("aws-success", "#!/bin/sh\nprintf '" + CREDENTIAL_LINE + "'\n");
    }

    private Path argvRecordingStub(Path argsFile) throws IOException {
        return writeScript(
                "aws-argv",
                "#!/bin/sh\n" + "for a in \"$@\"; do echo \"$a\" >> '" + argsFile + "'; done\n" + "printf '"
                        + CREDENTIAL_LINE + "'\n");
    }

    private Path failingStub() throws IOException {
        return writeScript(
                "aws-denied",
                "#!/bin/sh\n"
                        + "echo 'An error occurred (AccessDenied) when calling the AssumeRole operation' >&2\n"
                        + "exit 255\n");
    }

    private Path writeScript(String name, String body) throws IOException {
        Path script = tmp.resolve(name);
        Files.writeString(script, body, StandardCharsets.UTF_8);
        assertTrue(script.toFile().setExecutable(true), "stub script should be made executable");
        return script;
    }
}
