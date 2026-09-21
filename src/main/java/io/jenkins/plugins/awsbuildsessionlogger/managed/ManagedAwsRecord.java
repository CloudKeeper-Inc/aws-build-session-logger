package io.jenkins.plugins.awsbuildsessionlogger.managed;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.Run;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records where a build's decorated configuration was written, so it can be removed afterwards.
 *
 * <p>Separate from {@link ManagedAwsContext} only to keep that class's hot path free of persistence
 * concerns. The record is persisted with the build rather than held in memory, so cleanup still happens
 * if the controller restarts mid-build.
 */
final class ManagedAwsRecord {

    private static final Logger LOGGER = Logger.getLogger(ManagedAwsRecord.class.getName());

    private ManagedAwsRecord() {}

    static void record(Run<?, ?> run, @CheckForNull Node node, FilePath directory) {
        String nodeName = node == null ? "" : node.getNodeName();
        synchronized (run) {
            ManagedAwsAction action = run.getAction(ManagedAwsAction.class);
            boolean isNew = action == null;
            if (isNew) {
                action = new ManagedAwsAction();
                run.addAction(action);
            }
            boolean added = action.record(nodeName, directory.getRemote());
            if (isNew || added) {
                try {
                    // Persisted once per new location, so cleanup survives a controller restart.
                    run.save();
                } catch (IOException e) {
                    LOGGER.log(
                            Level.FINE,
                            e,
                            () -> "aws-build-session-logger: could not persist cleanup locations for " + run);
                }
            }
        }
    }
}
