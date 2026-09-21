package io.jenkins.plugins.awsbuildsessionlogger.managed;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.EnvironmentContributor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration;
import java.util.Map;
import java.util.Set;

/**
 * Managed Authentication for Freestyle builds.
 *
 * <p>{@link ManagedAwsContext} reaches every Pipeline build, but Pipeline's step machinery is the only
 * thing it hooks; a Freestyle build never passes through it. On a controller where Freestyle jobs upload
 * to S3 and back up Route 53, that is a hole in the audit rather than a limitation worth documenting.
 *
 * <p><b>Why an {@link EnvironmentContributor} and not {@code RunListener#setUpEnvironment}.</b> The
 * listener hook looks like the natural seam and is not: it runs <em>before</em> the workspace lease is
 * acquired, so {@code build.getWorkspace()} is still {@code null} and there is nowhere to write the
 * generated file. Measured, not assumed — the first implementation used it and contributed nothing. An
 * environment contributor runs when a build computes its environment, by which time the workspace
 * exists.
 *
 * <p><b>Freestyle only.</b> This contributor fires for every kind of build, including Pipeline, so it
 * deliberately handles {@link AbstractBuild} alone and leaves Pipeline to {@link ManagedAwsContext}.
 * Both would otherwise prepare the same build twice.
 *
 * <p><b>Everything that matters is shared.</b> This class decides <em>whether</em> to contribute and
 * where the workspace and node come from; the decoration, the safety check, the file, the memoization
 * and the cleanup record are {@link ManagedAwsContext#prepareOnce}. Two implementations of one
 * behaviour would drift, and the one that drifted would be the one nobody tested.
 *
 * <p><b>Fail-safe, identically.</b> The whole path runs inside the same guard, which swallows
 * {@link Throwable} and re-throws only {@link InterruptedException}. Anything unexpected contributes
 * nothing and the build authenticates exactly as it does without the plugin: a Freestyle job that works
 * today cannot be broken by this class.
 */
@Extension
public final class ManagedAwsFreestyleEnvironment extends EnvironmentContributor {

    @Override
    public void buildEnvironmentFor(@NonNull Run r, @NonNull EnvVars envs, @NonNull TaskListener listener)
            throws InterruptedException {
        Map<String, String> variables = ManagedAwsContext.guarded(() -> contribute(r, listener));
        if (variables != null) {
            // putAll, deliberately, despite the asymmetry with the Pipeline path.
            //
            // putIfAbsent looks like the additions-only invariant applied here, and it is not: the two
            // paths compare against different baselines. Job#getEnvironment fills this map with the
            // agent's OS environment and node properties BEFORE any EnvironmentContributor runs, whereas
            // the Pipeline invariant compares only against the enclosing context-level expander. So
            // putIfAbsent would defer to a node that merely exports AWS_CONFIG_FILE in its systemd unit
            // — a setup locateNodeConfig explicitly supports and prefers — and silently drop attribution
            // on that node while the console still printed "decorated as session jk-…".
            //
            // Losing attribution silently is worse than overriding a variable, and putAll is the
            // behaviour the Freestyle path was validated against in production. Distinguishing the
            // node's ambient environment from a deliberate job-level choice needs build variables and
            // wrappers, which is a change worth making with evidence rather than late in a rollout.
            envs.putAll(variables);
        }
    }

    @CheckForNull
    private static Map<String, String> contribute(Run<?, ?> run, TaskListener listener) throws InterruptedException {
        // Pipeline is handled by ManagedAwsContext; preparing it here as well would duplicate the work.
        if (!(run instanceof AbstractBuild)) {
            return null;
        }
        AbstractBuild<?, ?> build = (AbstractBuild<?, ?>) run;

        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        if (configuration == null || !configuration.isManagedAuthentication()) {
            return null;
        }
        if (!configuration.appliesTo(build.getParent().getFullName())) {
            return null;
        }

        Node node = build.getBuiltOn();
        if (!configuration.appliesToNode(labelsOf(node))) {
            return null;
        }

        // No workspace means no process that could consume this. Early calls to getEnvironment() —
        // before the lease is acquired — land here and contribute nothing; the call made when the build
        // actually runs a step has one.
        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            return null;
        }

        Computer computer = node == null ? null : node.toComputer();
        Map<String, String> variables =
                ManagedAwsContext.prepareOnce(build, workspace, node, computer, configuration, listener);

        // Observe only: prepared and reported, deliberately not exported. Identical to the Pipeline path
        // so the two modes cannot mean different things on different job types.
        return configuration.isObserveOnly() ? null : variables;
    }

    /** As for Pipeline: the built-in node reports no labels, so its node name is matched as well. */
    @NonNull
    private static Set<String> labelsOf(@CheckForNull Node node) {
        if (node == null) {
            return Set.of();
        }
        Set<String> labels = new java.util.LinkedHashSet<>();
        node.getAssignedLabels().forEach(label -> labels.add(label.getName()));
        String name = node.getNodeName();
        labels.add(name.isEmpty() ? "built-in" : name);
        return labels;
    }
}
