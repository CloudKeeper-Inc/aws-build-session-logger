package io.jenkins.plugins.awsbuildsessionlogger.auth;

/**
 * Thrown when the STS AssumeRole operation itself fails (transport error, denied request, empty
 * result, ...). Carries an actionable message including the role ARN and session name so the failure
 * can be surfaced usefully by a caller.
 */
public class AssumeRoleException extends AwsBuildSessionAuthException {

    private static final long serialVersionUID = 1L;

    public AssumeRoleException(String message) {
        super(message);
    }

    public AssumeRoleException(String message, Throwable cause) {
        super(message, cause);
    }
}
