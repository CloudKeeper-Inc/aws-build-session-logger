package io.jenkins.plugins.awsbuildsessionlogger.steps;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import io.jenkins.plugins.awsbuildsessionlogger.auth.AuthCore;
import io.jenkins.plugins.awsbuildsessionlogger.auth.AwsBuildSessionAuthException;
import io.jenkins.plugins.awsbuildsessionlogger.auth.AwsCredentials;
import io.jenkins.plugins.awsbuildsessionlogger.auth.SessionName;
import io.jenkins.plugins.awsbuildsessionlogger.auth.cli.CliStsAssumeRole;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsProfile;
import io.jenkins.plugins.awsbuildsessionlogger.exec.LauncherProcessRunner;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jenkins.util.SystemProperties;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.GeneralNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Pipeline step {@code withAwsBuildRole}: the plugin's public contract.
 *
 * <pre>{@code
 * node {
 *     withAwsBuildRole('non_prod') {
 *         sh 'aws sts get-caller-identity'
 *         sh 'terraform apply -auto-approve'
 *     }
 * }
 * }</pre>
 *
 * <p>Assumes a role with the load-bearing {@code jk-<job>-<build>} session name and publishes the
 * resulting temporary credentials into the block as the standard AWS environment variables. That
 * contract — not an argument list — is what makes the plugin consumable by every AWS tool: the CLI,
 * boto3, Terraform, {@code docker login} through a shell pipeline, and anything not yet written. The
 * plugin never executes the caller's commands.
 *
 * <p><b>Scoped, not returned.</b> Credentials are never a return value. A returned value lands in CPS
 * program state as plaintext and is trivially printable from a pipeline; a block can instead publish
 * them into the body's environment, mask them in the console, and let them fall out of scope — and is
 * the only shape with somewhere to put a future credential refresh.
 *
 * <p><b>Runs on the agent.</b> {@link Launcher} and {@link FilePath} are required context, so this step
 * only works inside a {@code node} block, and the AssumeRole subprocess runs on that node. The base
 * identity being chained from belongs to the agent, and the credentials are only useful to steps
 * running there.
 */
public final class WithAwsBuildRoleStep extends Step {

    /**
     * Overrides the {@code aws} executable used for the AssumeRole call. Exists so the step can be
     * exercised end-to-end without the real AWS CLI. Shares its name with the legacy
     * {@code awsAssumeBuildRole} step's hook so a test environment configures one thing, not two.
     */
    static final String AWS_EXECUTABLE_PROPERTY = "io.jenkins.plugins.awsbuildsessionlogger.awsExecutable";

    private static final String DEFAULT_AWS_EXECUTABLE = "aws";

    @CheckForNull
    private final String profile;

    @CheckForNull
    private String roleArn;

    @CheckForNull
    private String region;

    /**
     * @param profile the name of a profile configured in the global configuration. Nullable so the
     *     {@code roleArn} escape hatch can be used on its own; exactly one of the two is required, and
     *     that is enforced at execution time with an actionable message rather than by data binding.
     */
    @DataBoundConstructor
    public WithAwsBuildRoleStep(@CheckForNull String profile) {
        this.profile = trimToNull(profile);
    }

    @CheckForNull
    public String getProfile() {
        return profile;
    }

    @CheckForNull
    public String getRoleArn() {
        return roleArn;
    }

    /**
     * Escape hatch: assume this ARN directly instead of resolving a configured profile. Intended for
     * pipelines whose profile has not been added to the global configuration yet. This is not a
     * security boundary — a pipeline author who wants an arbitrary role can already shell out to
     * {@code aws sts assume-role}. The enforcement boundary is the IAM trust policy.
     */
    @DataBoundSetter
    public void setRoleArn(@CheckForNull String roleArn) {
        this.roleArn = trimToNull(roleArn);
    }

    @CheckForNull
    public String getRegion() {
        return region;
    }

    /** Overrides the region from the profile mapping. Optional; when absent no region is exported. */
    @DataBoundSetter
    public void setRegion(@CheckForNull String region) {
        this.region = trimToNull(region);
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(profile, roleArn, region, context);
    }

    @CheckForNull
    private static String trimToNull(@CheckForNull String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Runs off the CPS VM thread via {@link GeneralNonBlockingStepExecution}: the AssumeRole call blocks
     * on a subprocess, and blocking the CPS thread would stall the whole build's flow execution.
     */
    private static final class Execution extends GeneralNonBlockingStepExecution {

        private static final long serialVersionUID = 1L;

        // Only these three are held as state. The credentials deliberately are not: they live in the
        // expander and the log filter, which encrypt them, and nowhere else.
        @CheckForNull
        private final String profile;

        @CheckForNull
        private final String roleArn;

        @CheckForNull
        private final String region;

        Execution(
                @CheckForNull String profile,
                @CheckForNull String roleArn,
                @CheckForNull String region,
                StepContext context) {
            super(context);
            this.profile = profile;
            this.roleArn = roleArn;
            this.region = region;
        }

        @Override
        public boolean start() throws Exception {
            run(this::authenticateThenRunBody);
            return false;
        }

        private void authenticateThenRunBody() throws Exception {
            StepContext context = getContext();
            Run<?, ?> build = context.get(Run.class);
            TaskListener listener = context.get(TaskListener.class);
            Launcher launcher = context.get(Launcher.class);
            FilePath workspace = context.get(FilePath.class);
            if (build == null || listener == null || launcher == null || workspace == null) {
                throw new AbortException("withAwsBuildRole must run inside a 'node' block, so that the "
                        + "AssumeRole call happens on the agent that will use the credentials.");
            }

            Target target = resolveTarget();

            String jobName = build.getParent().getFullName();
            int buildNumber = build.getNumber();

            String sessionName;
            try {
                // Deterministic and side-effect-free, so computing it up front (AuthCore derives the same
                // value internally) fails closed before spawning a process and gives us a value to log.
                sessionName = SessionName.forBuild(jobName, buildNumber).value();
            } catch (AwsBuildSessionAuthException e) {
                throw abort(listener, e);
            }

            listener.getLogger()
                    .println("[aws-build-session-logger] Assuming role " + target.roleArn + " as session "
                            + sessionName);

            // 'node' allocates a workspace path but creates the directory lazily, on first use. This step
            // can be that first use - nothing in a build is obliged to touch the workspace before
            // authenticating - and a launcher refuses to start a process in a directory that does not
            // exist. Creating it here rather than inside LauncherProcessRunner keeps that class free of
            // filesystem side effects.
            workspace.mkdirs();

            AuthCore authCore =
                    new AuthCore(new CliStsAssumeRole(new LauncherProcessRunner(launcher, workspace), awsExecutable()));
            AwsCredentials credentials;
            try {
                credentials = authCore.authenticate(target.roleArn, jobName, buildNumber);
            } catch (AwsBuildSessionAuthException e) {
                throw abort(listener, e);
            }

            listener.getLogger()
                    .println("[aws-build-session-logger] Credentials available as session " + sessionName + " (expires "
                            + credentials.expiration() + ")");

            Map<String, String> secretVariables = new LinkedHashMap<>();
            secretVariables.put("AWS_ACCESS_KEY_ID", credentials.accessKeyId());
            secretVariables.put("AWS_SECRET_ACCESS_KEY", credentials.secretAccessKey());
            secretVariables.put("AWS_SESSION_TOKEN", credentials.sessionToken());

            Map<String, String> plainVariables = new LinkedHashMap<>();
            if (target.region != null) {
                // Both names: the CLI and most SDKs read AWS_REGION, older tooling reads AWS_DEFAULT_REGION.
                plainVariables.put("AWS_REGION", target.region);
                plainVariables.put("AWS_DEFAULT_REGION", target.region);
            }
            plainVariables.put("AWS_BUILD_SESSION_NAME", sessionName);

            context.newBodyInvoker()
                    .withContexts(
                            EnvironmentExpander.merge(
                                    context.get(EnvironmentExpander.class),
                                    new CredentialsEnvironmentExpander(secretVariables, plainVariables)),
                            BodyInvoker.mergeConsoleLogFilters(
                                    context.get(hudson.console.ConsoleLogFilter.class),
                                    new SecretMaskingConsoleLogFilter(List.copyOf(secretVariables.values()))))
                    .withCallback(new ReleaseCallback(sessionName))
                    .start();
        }

        /**
         * Decides which role to assume, failing closed. Guessing an identity is worse than refusing to
         * pick one, so every ambiguous or unresolvable case aborts with a message naming what to fix.
         */
        private Target resolveTarget() throws AbortException {
            if (profile != null && roleArn != null) {
                throw new AbortException("withAwsBuildRole accepts either 'profile' or 'roleArn', not both. "
                        + "Got profile '" + profile + "' and roleArn '" + roleArn + "'.");
            }
            if (roleArn != null) {
                return new Target(roleArn, region);
            }
            if (profile == null) {
                throw new AbortException("withAwsBuildRole requires a profile name, e.g. "
                        + "withAwsBuildRole('non_prod') { ... }" + configuredProfilesHint());
            }

            AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
            Optional<AwsProfile> resolved = configuration == null ? Optional.empty() : configuration.resolve(profile);
            if (resolved.isEmpty()) {
                throw new AbortException("No AWS profile named '" + profile
                        + "' is configured in Jenkins." + configuredProfilesHint()
                        + " Profiles are configured as code, under unclassified.awsBuildSessionLogger.profiles "
                        + "(Configuration as Code), or in the plugin's global configuration XML.");
            }
            AwsProfile awsProfile = resolved.get();
            if (!awsProfile.hasRole()) {
                // A profile with no role ARN means "use the agent's own identity" — a legitimate
                // configuration for Managed Authentication, but nothing this step can assume. Refusing
                // is better than silently running as the agent under a name that implies otherwise.
                throw new AbortException("The AWS profile '" + profile + "' has no role ARN configured, so "
                        + "there is nothing for withAwsBuildRole to assume. Give the profile a roleArn "
                        + "under unclassified.awsBuildSessionLogger.profiles, or pass roleArn: explicitly.");
            }
            return new Target(awsProfile.getRoleArn(), region != null ? region : awsProfile.getRegion());
        }

        private static String configuredProfilesHint() {
            AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
            List<String> names = configuration == null ? List.of() : configuration.configuredProfileNames();
            if (names.isEmpty()) {
                return " No profiles are configured.";
            }
            return " Configured profiles: " + String.join(", ", names) + ".";
        }

        private static String awsExecutable() {
            // Same guard as AwsAssumeBuildRoleStep. A property set to the empty string — a JCasC template
            // rendering an unset variable, say — otherwise reaches CliStsAssumeRole's constructor, which
            // throws IllegalArgumentException from OUTSIDE the try that converts failures into
            // AbortException. The user then gets a raw stack trace instead of an actionable message,
            // after the workspace has already been created.
            String configured = SystemProperties.getString(AWS_EXECUTABLE_PROPERTY, DEFAULT_AWS_EXECUTABLE);
            return configured == null || configured.trim().isEmpty() ? DEFAULT_AWS_EXECUTABLE : configured;
        }

        /**
         * Turns an auth-layer failure into the Jenkins idiom for an expected, user-actionable error:
         * {@link AbortException} prints its message alone, with no Java stack trace. The root cause's
         * <em>message</em> is logged separately so diagnostics are not lost, but never the exception
         * object, whose class name would leak the auth layer's transport.
         */
        private static AbortException abort(TaskListener listener, AwsBuildSessionAuthException e) {
            String detail = rootCauseMessage(e);
            if (detail != null) {
                listener.getLogger().println("[aws-build-session-logger] cause: " + detail);
            }
            return new AbortException(e.getMessage());
        }

        @CheckForNull
        private static String rootCauseMessage(Throwable e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return root == e ? null : root.getMessage();
        }
    }

    /** The resolved outcome of profile lookup: what to assume, and where. */
    private static final class Target implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String roleArn;

        @CheckForNull
        private final String region;

        Target(String roleArn, @CheckForNull String region) {
            this.roleArn = roleArn;
            this.region = region;
        }
    }

    /**
     * Logs the end of the authenticated scope. {@link BodyExecutionCallback.TailCall} propagates the
     * body's own result or failure unchanged, so this adds a log line without altering build outcome.
     *
     * <p>There is nothing to clean up: the credentials only ever existed inside the body's environment,
     * so they cease to exist when the body does.
     */
    private static final class ReleaseCallback extends BodyExecutionCallback.TailCall {

        private static final long serialVersionUID = 1L;

        private final String sessionName;

        ReleaseCallback(String sessionName) {
            this.sessionName = sessionName;
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            TaskListener listener = context.get(TaskListener.class);
            if (listener != null) {
                listener.getLogger()
                        .println("[aws-build-session-logger] Released credentials for session " + sessionName);
            }
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "withAwsBuildRole";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Authenticate to AWS for a block of steps";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        /**
         * {@link Launcher} and {@link FilePath} are required deliberately: they are only available inside
         * a {@code node} block, which is exactly where this step must run. Declaring them means Jenkins
         * produces its own clear "required context" error before the step body ever executes.
         */
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, Launcher.class, FilePath.class);
        }

        public FormValidation doCheckProfile(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok(); // may be supplied via the roleArn escape hatch instead
            }
            AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
            if (configuration == null) {
                return FormValidation.ok();
            }
            if (configuration.resolve(value).isPresent()) {
                return FormValidation.ok();
            }
            List<String> names = configuration.configuredProfileNames();
            if (names.isEmpty()) {
                return FormValidation.warning("No AWS profiles are configured yet. "
                        + "Add them as code under unclassified.awsBuildSessionLogger.profiles.");
            }
            return FormValidation.warning("No profile named '" + value.trim() + "' is configured. "
                    + "Configured profiles: " + String.join(", ", names) + ".");
        }
    }
}
