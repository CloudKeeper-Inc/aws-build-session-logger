package io.jenkins.plugins.awsbuildsessionlogger.auth;

/**
 * Base type for all authentication-core failures.
 *
 * <p>Unchecked on purpose: the auth core does not force callers to handle
 * failures. The future Jenkins integration layer decides how to surface them (fail the build with an
 * actionable message, etc.) rather than having that policy baked in here.
 */
public class AwsBuildSessionAuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AwsBuildSessionAuthException(String message) {
        super(message);
    }

    public AwsBuildSessionAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
