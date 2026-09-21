package io.jenkins.plugins.awsbuildsessionlogger.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable temporary AWS credentials produced by an AssumeRole call.
 *
 * <p>The {@link #expiration()} field is the single hook that makes a future credential-refresh path
 * possible without changing this model: expiry is evaluated against an injected {@link Clock}, so
 * callers (and later a refreshing decorator) can decide when to re-authenticate. No caching or refresh
 * scheduling lives here — that is deliberately out of scope.
 *
 * <p>{@link #toString()} redacts the secret access key and session token so credentials are never
 * leaked into logs.
 */
public final class AwsCredentials {

    private final String accessKeyId;
    private final String secretAccessKey;
    private final String sessionToken;
    private final Instant expiration;

    public AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken, Instant expiration) {
        this.accessKeyId = requireNonBlank(accessKeyId, "accessKeyId");
        this.secretAccessKey = requireNonBlank(secretAccessKey, "secretAccessKey");
        this.sessionToken = requireNonBlank(sessionToken, "sessionToken");
        this.expiration = Objects.requireNonNull(expiration, "expiration");
    }

    private static String requireNonBlank(String value, String field) {
        // "None" is how `--output text` renders a null field. It is not blank, so it passed every check
        // and the build carried on with AWS_SESSION_TOKEN=None — then failed opaquely much later, inside
        // whatever tool actually used the credentials.
        if ("None".equals(value == null ? null : value.trim())) {
            throw new IllegalArgumentException(
                    field + " came back as 'None', which is how the AWS CLI renders a missing value.");
        }
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be null or blank.");
        }
        return value;
    }

    public String accessKeyId() {
        return accessKeyId;
    }

    public String secretAccessKey() {
        return secretAccessKey;
    }

    public String sessionToken() {
        return sessionToken;
    }

    public Instant expiration() {
        return expiration;
    }

    /**
     * @return {@code true} if these credentials are at or past their expiration instant relative to the
     *     given clock.
     */
    public boolean isExpired(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return !expiration.isAfter(clock.instant());
    }

    /**
     * @return {@code true} if these credentials are already expired or will expire within {@code window}
     *     from now. Useful for a future refresh path that renews credentials slightly ahead of expiry.
     */
    public boolean expiresWithin(Duration window, Clock clock) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(clock, "clock");
        return !expiration.isAfter(clock.instant().plus(window));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AwsCredentials)) {
            return false;
        }
        AwsCredentials that = (AwsCredentials) o;
        return accessKeyId.equals(that.accessKeyId)
                && secretAccessKey.equals(that.secretAccessKey)
                && sessionToken.equals(that.sessionToken)
                && expiration.equals(that.expiration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accessKeyId, secretAccessKey, sessionToken, expiration);
    }

    @Override
    public String toString() {
        return "AwsCredentials{accessKeyId=" + accessKeyId + ", secretAccessKey=***, sessionToken=***, expiration="
                + expiration + "}";
    }
}
