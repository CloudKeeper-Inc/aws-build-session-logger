package io.jenkins.plugins.awsbuildsessionlogger.managed;

import hudson.Extension;
import hudson.FilePath;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * A stand-in for {@code dir}, changing the working directory exactly the way it does.
 *
 * <p>{@code PushdStep} publishes one thing to its block: a new {@link FilePath}. It contributes no
 * {@link org.jenkinsci.plugins.workflow.steps.EnvironmentExpander}, which is precisely what makes it
 * interesting — it creates a context level that has no expander of its own, so a lookup starting there
 * must walk up to find the enclosing one.
 *
 * <p>Reproduced here rather than depended on, for the baseline reason given in {@link FakeBindingStep}.
 * The same shape is created by {@code ws}, {@code container}, {@code docker.inside} and any other block
 * step that publishes a context object that is not an expander.
 */
public final class FakeDirStep extends Step {

    private final String path;

    @DataBoundConstructor
    public FakeDirStep(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, path);
    }

    private static final class Execution extends StepExecution {

        private static final long serialVersionUID = 1L;

        private final String path;

        Execution(StepContext context, String path) {
            super(context);
            this.path = path;
        }

        @Override
        public boolean start() throws Exception {
            StepContext context = getContext();
            FilePath cwd = context.get(FilePath.class).child(path);
            cwd.mkdirs();
            context.newBodyInvoker()
                    .withContext(cwd)
                    .withCallback(BodyExecutionCallback.wrap(context))
                    .start();
            return false;
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "fakeDir";
        }

        @Override
        public String getDisplayName() {
            return "Test double for dir";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(FilePath.class);
        }
    }
}
