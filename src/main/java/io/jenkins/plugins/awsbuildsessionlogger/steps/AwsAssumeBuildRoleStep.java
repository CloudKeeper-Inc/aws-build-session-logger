package io.jenkins.plugins.awsbuildsessionlogger.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import io.jenkins.plugins.awsbuildsessionlogger.auth.AuthCore;
import io.jenkins.plugins.awsbuildsessionlogger.auth.AwsBuildSessionAuthException;
import io.jenkins.plugins.awsbuildsessionlogger.auth.SessionName;
import io.jenkins.plugins.awsbuildsessionlogger.auth.cli.CliStsAssumeRole;
import io.jenkins.plugins.awsbuildsessionlogger.exec.DefaultProcessRunner;
import java.util.Set;
import jenkins.util.SystemProperties;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Pipeline step {@code awsAssumeBuildRole}: the original Jenkins integration point.
 *
 * <p><b>Superseded by {@link WithAwsBuildRoleStep} ({@code withAwsBuildRole}), and scheduled for
 * removal.</b> Two reasons, both architectural rather than cosmetic: it discards the credentials it
 * obtains, so nothing downstream can use them; and it executes on the Jenkins controller, so it
 * authenticates with the controller's identity rather than that of the agent which would do the work.
 * It is retained unchanged only because it is the step the live-AWS and CloudTrail validation was
 * performed with, and removing it would invalidate that evidence. Do not build on it.
 *
 * <pre>{@code
 * def session = awsAssumeBuildRole(roleArn: 'arn:aws:iam::123456789012:role/non_prod')
 * echo "assumed as ${session}"
 * }</pre>
 *
 * <p>Its whole job is to bridge Jenkins to the existing, Jenkins-agnostic auth stack: read the job name
 * and build number off the running build, hand them plus the role ARN to {@link AuthCore}, and translate
 * failures into an {@link AbortException} so the build log shows an actionable message rather than a
 * stack trace. Everything below it — {@link AuthCore}, {@link CliStsAssumeRole},
 * {@link DefaultProcessRunner} — is Jenkins-agnostic.
 *
 * <p><b>The step returns only the generated session name.</b> No credential material (not even the
 * access key id) is exposed to the Pipeline DSL: anything returned here is persisted in the CPS program
 * state and is trivially printable from a pipeline. Exporting credentials for subsequent steps is the
 * job of a future block-scoped {@code withProfile} step, not this one.
 *
 * <p><b>Known limitation:</b> this executes on the Jenkins controller JVM (via
 * {@link DefaultProcessRunner}), not on the agent selected by any enclosing {@code node} block. An agent-backed {@code ProcessRunner} built from the step
 * context's {@code Launcher} can be added later as a new {@code .exec} implementation, with no change to
 * the auth layer.
 */
public final class AwsAssumeBuildRoleStep extends Step {

    /**
     * Overrides the {@code aws} executable used for the AssumeRole call. Exists so the step can be
     * exercised end-to-end without the real AWS CLI (the child process inherits this JVM's environment,
     * so a test cannot simply prepend to {@code PATH}).
     */
    static final String AWS_EXECUTABLE_PROPERTY = "io.jenkins.plugins.awsbuildsessionlogger.awsExecutable";

    private static final String DEFAULT_AWS_EXECUTABLE = "aws";

    private final String roleArn;

    @DataBoundConstructor
    public AwsAssumeBuildRoleStep(String roleArn) {
        // Deliberately permissive: validation happens in the execution, so a bad value fails the build
        // with a readable message instead of an opaque data-binding error.
        this.roleArn = roleArn;
    }

    public String getRoleArn() {
        return roleArn;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(roleArn, context);
    }

    /**
     * Runs off the CPS VM thread: the AssumeRole call blocks on a subprocess, and blocking the CPS
     * thread would stall the whole build's flow execution.
     */
    private static final class Execution extends SynchronousNonBlockingStepExecution<String> {

        private static final long serialVersionUID = 1L;

        /** Only serializable state is held here; {@link AuthCore} is built per invocation in {@link #run()}. */
        private final String roleArn;

        Execution(String roleArn, StepContext context) {
            super(context);
            this.roleArn = roleArn;
        }

        @Override
        protected String run() throws Exception {
            String arn = roleArn == null ? "" : roleArn.trim();
            if (arn.isEmpty()) {
                throw new AbortException("awsAssumeBuildRole requires a non-blank roleArn, e.g. "
                        + "awsAssumeBuildRole(roleArn: 'arn:aws:iam::123456789012:role/non_prod')");
            }

            Run<?, ?> build = getContext().get(Run.class);
            TaskListener listener = getContext().get(TaskListener.class);
            if (build == null || listener == null) {
                throw new AbortException("awsAssumeBuildRole must run in the context of a build.");
            }

            // getFullName(), not getName(): folder jobs contribute their folder path, which SessionName
            // sanitizes. This is where Jenkins identity enters the auth stack.
            String jobName = build.getParent().getFullName();
            int buildNumber = build.getNumber();

            String sessionName;
            try {
                // Deterministic and side-effect-free, so computing it up front (AuthCore derives the same
                // value internally) both fails closed before spawning a process and gives us the value to
                // return, without changing AuthCore's signature.
                sessionName = SessionName.forBuild(jobName, buildNumber).value();
            } catch (AwsBuildSessionAuthException e) {
                throw abort(listener, e);
            }

            listener.getLogger()
                    .println("[aws-build-session-logger] Assuming role " + arn + " as session " + sessionName);

            AuthCore authCore = new AuthCore(new CliStsAssumeRole(new DefaultProcessRunner(), awsExecutable()));
            try {
                // The credentials are intentionally discarded: exposing or exporting them is the job of a
                // future block-scoped withProfile step, not this one.
                authCore.authenticate(arn, jobName, buildNumber);
            } catch (AwsBuildSessionAuthException e) {
                throw abort(listener, e);
            }

            listener.getLogger()
                    .println("[aws-build-session-logger] Assumed role " + arn + " as session " + sessionName);
            return sessionName;
        }

        /**
         * Turns an auth-layer failure into the Jenkins idiom for an expected, user-actionable error:
         * {@link AbortException} prints its message alone, with no Java stack trace.
         *
         * <p>The root cause's <em>message</em> is logged separately so diagnostics are not lost — but
         * never the exception object, whose class name would leak the auth layer's transport (the
         * boundary that keeps "authentication happens via a subprocess" invisible to callers).
         */
        private static AbortException abort(TaskListener listener, AwsBuildSessionAuthException e) {
            String detail = rootCauseMessage(e);
            if (detail != null) {
                listener.getLogger().println("[aws-build-session-logger] cause: " + detail);
            }
            return new AbortException(e.getMessage());
        }

        private static String rootCauseMessage(Throwable e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return root == e ? null : root.getMessage();
        }

        private static String awsExecutable() {
            String configured = SystemProperties.getString(AWS_EXECUTABLE_PROPERTY, DEFAULT_AWS_EXECUTABLE);
            return configured == null || configured.trim().isEmpty() ? DEFAULT_AWS_EXECUTABLE : configured;
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "awsAssumeBuildRole";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Assume an AWS role for this build";
        }

        /** Declaring this makes misuse fail with a clear "step requires ..." message. */
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }

        /**
         * Snippet-generator validation. Deliberately not a full ARN regex — over-validating would reject
         * valid partition/ARN forms — so a non-ARN-looking value is only a warning.
         */
        public FormValidation doCheckRoleArn(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("A role ARN is required.");
            }
            if (!value.trim().startsWith("arn:")) {
                return FormValidation.warning("This does not look like an ARN (expected something like "
                        + "arn:aws:iam::<account>:role/<name>).");
            }
            return FormValidation.ok();
        }
    }
}
