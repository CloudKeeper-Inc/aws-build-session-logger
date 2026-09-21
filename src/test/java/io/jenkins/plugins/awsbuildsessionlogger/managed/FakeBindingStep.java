package io.jenkins.plugins.awsbuildsessionlogger.managed;

import hudson.Extension;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * A stand-in for {@code withCredentials}, contributing environment exactly the way it does.
 *
 * <p>This exists because {@code workflow-basic-steps} and {@code credentials-binding} cannot be test
 * dependencies of this plugin: both drag in {@code instance-identity} 203.x, which, when this
 * was written, declared a newer Jenkins-Version than the plugin's baseline. The {@code validate-hpi}
 * goal rejects that even at test scope.
 *
 * <p>What matters for the defect under test is not the credentials machinery but the single line
 * {@code CredentialsBindingStep} uses to publish its bindings:
 *
 * <pre>
 * withContext(EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class), ...))
 * </pre>
 *
 * <p>That is reproduced verbatim below. Any plugin that contributes environment to a block — {@code
 * withEnv}, {@code withAWS}, {@code withSonarQubeEnv}, {@code configFileProvider}, {@code withMaven} —
 * publishes it the same way, so a defect reproduced here affects all of them.
 */
public final class FakeBindingStep extends Step {

    /** What a binding step puts into the environment of its block. */
    static final Map<String, String> BINDINGS =
            Map.of("REPO_USERNAME", "repo-user", "AWS_CONFIG_FILE", "/job/owned/config");

    @DataBoundConstructor
    public FakeBindingStep() {}

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context);
    }

    private static final class Execution extends StepExecution {

        private static final long serialVersionUID = 1L;

        Execution(StepContext context) {
            super(context);
        }

        @Override
        public boolean start() throws Exception {
            StepContext context = getContext();
            context.newBodyInvoker()
                    .withContext(EnvironmentExpander.merge(
                            context.get(EnvironmentExpander.class), EnvironmentExpander.constant(BINDINGS)))
                    .withCallback(BodyExecutionCallback.wrap(context))
                    .start();
            return false;
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "fakeBinding";
        }

        @Override
        public String getDisplayName() {
            return "Test double for withCredentials";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of();
        }
    }
}
