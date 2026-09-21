package io.jenkins.plugins.awsbuildsessionlogger.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import hudson.util.FormValidation;
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
 * Tests for the Jenkins integration point.
 *
 * <p>{@link JenkinsRule} is genuinely required here — what is under test <em>is</em> extension
 * registration and DSL wiring. Every test drives the real path Step -&gt; AuthCore -&gt; CliStsAssumeRole -&gt;
 * DefaultProcessRunner -&gt; a real subprocess, with a stub {@code aws} script standing in for the AWS
 * CLI. No AWS account, no credentials and no installed AWS CLI are needed.
 *
 * <p>POSIX-only (the stubs are {@code /bin/sh} scripts), matching {@code DefaultProcessRunnerTest}.
 */
@WithJenkins
@DisabledOnOs(OS.WINDOWS)
class AwsAssumeBuildRoleStepTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/non_prod";

    /** One tab-separated line, exactly as {@code --query Credentials.[...] --output text} produces. */
    private static final String CREDENTIAL_LINE =
            "ASIAEXAMPLE\\tSECRET/EXAMPLE+VALUE\\tSESSIONTOKENVALUE==\\t2026-07-24T13:00:00+00:00\\n";

    @TempDir
    private Path tmp;

    @AfterEach
    void clearExecutableOverride() {
        System.clearProperty(AwsAssumeBuildRoleStep.AWS_EXECUTABLE_PROPERTY);
    }

    @Test
    void stepIsRegisteredWithItsFunctionName(JenkinsRule j) {
        StepDescriptor descriptor = StepDescriptor.byFunctionName("awsAssumeBuildRole");
        assertNotNull(descriptor, "awsAssumeBuildRole step should be registered");
        assertInstanceOf(AwsAssumeBuildRoleStep.DescriptorImpl.class, descriptor);
        assertTrue(
                descriptor.getRequiredContext().contains(hudson.model.Run.class),
                "step should declare Run as required context");
    }

    @Test
    void returnsTheGeneratedSessionNameOnSuccess(JenkinsRule j) throws Exception {
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "myjob");
        job.setDefinition(new CpsFlowDefinition(assertingScript("jk-myjob-1"), true));

        // The build only succeeds if the in-pipeline assertion on the returned value held.
        WorkflowRun build = j.buildAndAssertSuccess(job);
        j.assertLogContains("[aws-build-session-logger] Assumed role " + ROLE + " as session jk-myjob-1", build);
    }

    @Test
    void jobNameAndBuildNumberReachTheAssumeRoleCall(JenkinsRule j) throws Exception {
        Path args = tmp.resolve("argv.txt");
        useStub(argvRecordingStub(args));

        WorkflowJob job = j.createProject(WorkflowJob.class, "paramflow");
        job.setDefinition(new CpsFlowDefinition("awsAssumeBuildRole(roleArn: '" + ROLE + "')\n", true));
        j.buildAndAssertSuccess(job);

        List<String> argv = Files.readAllLines(args, StandardCharsets.UTF_8);
        assertEquals("sts", argv.get(0));
        assertEquals("assume-role", argv.get(1));
        assertEquals(ROLE, argv.get(argv.indexOf("--role-arn") + 1), "roleArn from the DSL should reach the CLI");
        assertEquals(
                "jk-paramflow-1",
                argv.get(argv.indexOf("--role-session-name") + 1),
                "job name and build number from Jenkins should reach the CLI as the session name");
    }

    @Test
    void awkwardJobNamesAreSanitizedIntoAValidSessionName(JenkinsRule j) throws Exception {
        useStub(successStub());

        // Jenkins rejects most punctuation in job names, but spaces are legal - and they are outside
        // STS's [\w+=,.@-] set, so this exercises SessionName's sanitizer through the real step.
        WorkflowJob job = j.createProject(WorkflowJob.class, "my awkward job");
        job.setDefinition(new CpsFlowDefinition(assertingScript("jk-my-awkward-job-1"), true));

        j.buildAndAssertSuccess(job);
    }

    @Test
    void assumeRoleFailureAbortsTheBuildWithAnActionableMessage(JenkinsRule j) throws Exception {
        useStub(failingStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "denied");
        job.setDefinition(new CpsFlowDefinition("awsAssumeBuildRole(roleArn: '" + ROLE + "')\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        String log = JenkinsRule.getLog(build);

        assertTrue(log.contains(ROLE), "should name the role");
        assertTrue(log.contains("jk-denied-1"), "should name the session");
        assertTrue(log.contains("255"), "should report the exit code");
        assertTrue(log.contains("AccessDenied"), "should surface stderr");
        assertFalse(
                log.contains("\tat io.jenkins.plugins.awsbuildsessionlogger"), "should not print a Java stack trace");
    }

    @Test
    void missingAwsExecutableAbortsTheBuildCleanly(JenkinsRule j) throws Exception {
        System.setProperty(
                AwsAssumeBuildRoleStep.AWS_EXECUTABLE_PROPERTY,
                tmp.resolve("no-such-aws").toString());

        WorkflowJob job = j.createProject(WorkflowJob.class, "nobinary");
        job.setDefinition(new CpsFlowDefinition("awsAssumeBuildRole(roleArn: '" + ROLE + "')\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        String log = JenkinsRule.getLog(build);

        assertTrue(log.contains(ROLE), "should name the role");
        assertFalse(log.contains("ProcessExecutionException"), "execution-layer exception must not leak");
        assertFalse(
                log.contains("\tat io.jenkins.plugins.awsbuildsessionlogger"), "should not print a Java stack trace");
    }

    @Test
    void blankRoleArnAbortsTheBuild(JenkinsRule j) throws Exception {
        useStub(successStub());

        WorkflowJob job = j.createProject(WorkflowJob.class, "noarn");
        job.setDefinition(new CpsFlowDefinition("awsAssumeBuildRole(roleArn: '  ')\n", true));

        WorkflowRun build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.assertLogContains("requires a non-blank roleArn", build);
    }

    @Test
    void roleArnFormValidation(JenkinsRule j) {
        AwsAssumeBuildRoleStep.DescriptorImpl descriptor =
                (AwsAssumeBuildRoleStep.DescriptorImpl) StepDescriptor.byFunctionName("awsAssumeBuildRole");
        assertNotNull(descriptor);

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckRoleArn("").kind);
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckRoleArn("non_prod").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckRoleArn(ROLE).kind);
    }

    // --- pipeline scripts ---------------------------------------------------

    /**
     * Asserts the step's return value with plain Groovy rather than {@code echo}, so the tests need no
     * dependency on workflow-basic-steps. A mismatch fails the build.
     */
    private static String assertingScript(String expectedSessionName) {
        return "def session = awsAssumeBuildRole(roleArn: '" + ROLE + "')\n" + "assert session == '"
                + expectedSessionName + "'\n";
    }

    // --- stub 'aws' scripts -------------------------------------------------

    private void useStub(Path stub) {
        System.setProperty(AwsAssumeBuildRoleStep.AWS_EXECUTABLE_PROPERTY, stub.toString());
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
