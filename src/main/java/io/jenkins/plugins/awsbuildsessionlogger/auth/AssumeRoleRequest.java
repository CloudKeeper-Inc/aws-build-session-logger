package io.jenkins.plugins.awsbuildsessionlogger.auth;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Immutable inputs to a single STS AssumeRole call: the target role ARN, the {@link SessionName}, and
 * an optional requested session duration.
 *
 * <p>The duration is optional because of the known role-chaining constraint (EC2 instance role -&gt;
 * target role): chained sessions are capped at <b>3600 seconds</b> regardless of the role's configured
 * max session duration. When present it is validated against STS's {@code [900, 3600]} range for this
 * chained case. Requesting/refreshing is not implemented here — this type only carries the value.
 */
public final class AssumeRoleRequest {

    /** STS minimum for DurationSeconds. */
    private static final int MIN_DURATION_SECONDS = 900;

    /** Effective maximum under role chaining (see class doc). */
    private static final int MAX_CHAINED_DURATION_SECONDS = 3600;

    private final String roleArn;
    private final SessionName sessionName;
    private final Integer durationSeconds; // null == "let STS/role decide"

    private AssumeRoleRequest(String roleArn, SessionName sessionName, Integer durationSeconds) {
        this.roleArn = requireNonBlank(roleArn, "roleArn");
        this.sessionName = Objects.requireNonNull(sessionName, "sessionName");
        this.durationSeconds = durationSeconds;
    }

    /** A request with no explicit duration (STS/role default applies, capped at 1h by chaining). */
    public static AssumeRoleRequest of(String roleArn, SessionName sessionName) {
        return new AssumeRoleRequest(roleArn, sessionName, null);
    }

    /**
     * A request with an explicit duration.
     *
     * @throws IllegalArgumentException if {@code durationSeconds} is outside the chained-session range
     *     {@code [900, 3600]}
     */
    public static AssumeRoleRequest of(String roleArn, SessionName sessionName, int durationSeconds) {
        if (durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_CHAINED_DURATION_SECONDS) {
            throw new IllegalArgumentException("durationSeconds must be within [" + MIN_DURATION_SECONDS + ", "
                    + MAX_CHAINED_DURATION_SECONDS + "] for a chained session but was " + durationSeconds + ".");
        }
        return new AssumeRoleRequest(roleArn, sessionName, durationSeconds);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be null or blank.");
        }
        return value;
    }

    public String roleArn() {
        return roleArn;
    }

    public SessionName sessionName() {
        return sessionName;
    }

    public OptionalInt durationSeconds() {
        return durationSeconds == null ? OptionalInt.empty() : OptionalInt.of(durationSeconds);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AssumeRoleRequest)) {
            return false;
        }
        AssumeRoleRequest that = (AssumeRoleRequest) o;
        return roleArn.equals(that.roleArn)
                && sessionName.equals(that.sessionName)
                && Objects.equals(durationSeconds, that.durationSeconds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleArn, sessionName, durationSeconds);
    }

    @Override
    public String toString() {
        return "AssumeRoleRequest{roleArn=" + roleArn + ", sessionName=" + sessionName.value() + ", durationSeconds="
                + (durationSeconds == null ? "<default>" : durationSeconds) + "}";
    }
}
