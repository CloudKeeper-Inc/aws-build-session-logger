package io.jenkins.plugins.awsbuildsessionlogger.auth;

/**
 * A source of currently-valid AWS credentials.
 *
 * <p>This is a forward-looking seam, not wired to anything yet. A caching/refreshing
 * implementation could wrap {@link AuthCore} and use {@link AwsCredentials#expiresWithin} to
 * re-authenticate before expiry — without changing the core.
 */
@FunctionalInterface
public interface CredentialsProvider {

    /**
     * @return valid credentials, re-authenticating if necessary
     * @throws AwsBuildSessionAuthException if credentials cannot be obtained
     */
    AwsCredentials get();
}
