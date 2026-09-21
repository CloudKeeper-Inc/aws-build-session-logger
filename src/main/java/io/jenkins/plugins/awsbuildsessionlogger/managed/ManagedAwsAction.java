package io.jenkins.plugins.awsbuildsessionlogger.managed;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.InvisibleAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Records where Managed Authentication wrote a build's decorated configuration, so it can be removed
 * when the build finishes.
 *
 * <p>Persisted with the build rather than held in memory, so cleanup still happens if the controller
 * restarts mid-build. Invisible because it is bookkeeping, not something a user should see.
 *
 * <p>Nothing here is sensitive: these are directory paths, and the file at them contains no credential
 * material — only the node's own configuration plus a session name.
 *
 * <p><b>Why a nested type rather than a delimited string.</b> An earlier version packed
 * {@code node + separator + path} into one string, which meant choosing a character that can appear in
 * neither. The first choice was {@code NUL}, and Jenkins persists actions as XML, where {@code 0x00} is
 * not a legal character at all — every build logged a serialization failure and no cleanup location
 * survived. Modelling the pair removes the question instead of answering it more carefully.
 */
public final class ManagedAwsAction extends InvisibleAction {

    private final List<Location> locations = new ArrayList<>();

    /** @return {@code true} if this location was not already recorded. */
    public synchronized boolean record(@NonNull String nodeName, @NonNull String remotePath) {
        Location location = new Location(nodeName, remotePath);
        if (locations.contains(location)) {
            return false;
        }
        locations.add(location);
        return true;
    }

    @NonNull
    public synchronized List<Location> locations() {
        return Collections.unmodifiableList(new ArrayList<>(locations));
    }

    /** One generated directory: the node it lives on, and its absolute path there. */
    public static final class Location {

        private final String node;
        private final String path;

        Location(String node, String path) {
            this.node = node;
            this.path = path;
        }

        /** @return the node's name; empty for the built-in node. */
        @NonNull
        public String getNode() {
            return node == null ? "" : node;
        }

        @NonNull
        public String getPath() {
            return path == null ? "" : path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Location)) {
                return false;
            }
            Location that = (Location) o;
            return getNode().equals(that.getNode()) && getPath().equals(that.getPath());
        }

        @Override
        public int hashCode() {
            return Objects.hash(getNode(), getPath());
        }

        @Override
        public String toString() {
            return "Location{node=" + getNode() + ", path=" + getPath() + "}";
        }
    }
}
