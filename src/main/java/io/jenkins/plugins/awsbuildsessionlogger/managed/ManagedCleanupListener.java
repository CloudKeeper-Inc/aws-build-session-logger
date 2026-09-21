package io.jenkins.plugins.awsbuildsessionlogger.managed;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Removes what Managed Authentication generated, and forgets the build.
 *
 * <p>{@code onFinalized} fires for every terminal state — including {@code ABORTED}, which is the case
 * a post-build step would miss.
 *
 * <p>Deletion is <b>best effort by design</b>. The generated file is a copy of the node's own AWS
 * configuration plus a session name, and holds no credential material. Nothing here is a security control, so an agent that
 * happens to be offline at finalize costs a kilobyte, not an exposure — and because the generated
 * directory has a stable path that every build overwrites, a missed cleanup is reclaimed by the next
 * build in that workspace rather than accumulating. That is why this plugin needs no sweeper thread,
 * no scheduled task and no orphan-collection service.
 */
@Extension
public final class ManagedCleanupListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(ManagedCleanupListener.class.getName());

    @Override
    public void onFinalized(@NonNull Run<?, ?> run) {
        ManagedAwsContext.forget(run);

        ManagedAwsAction action = run.getAction(ManagedAwsAction.class);
        if (action == null) {
            return;
        }
        for (ManagedAwsAction.Location location : action.locations()) {
            delete(run, location.getNode(), location.getPath());
        }
    }

    private static void delete(Run<?, ?> run, String nodeName, String remotePath) {
        try {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins == null) {
                return;
            }
            Node node = nodeName.isEmpty() ? jenkins : jenkins.getNode(nodeName);
            if (node == null) {
                return; // the agent has been removed; its disk went with it
            }
            FilePath directory = node.createPath(remotePath);
            if (directory == null) {
                return; // the agent is offline
            }
            directory.deleteRecursive();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, e, () -> "aws-build-session-logger: could not remove " + remotePath + " for " + run);
        }
    }
}
