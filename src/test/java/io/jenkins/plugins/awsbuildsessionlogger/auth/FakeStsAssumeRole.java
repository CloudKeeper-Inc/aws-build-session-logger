package io.jenkins.plugins.awsbuildsessionlogger.auth;

/**
 * Hand-written test double for {@link StsAssumeRole}. No mocking framework, no network, no Jenkins.
 *
 * <p>Records the last request it received (so tests can assert the session name / role ARN the core
 * built) and can be configured to either return preset credentials or throw a preset failure.
 */
final class FakeStsAssumeRole implements StsAssumeRole {

    private AssumeRoleRequest lastRequest;
    private AwsCredentials toReturn;
    private RuntimeException toThrow;

    /** Configure the fake to return the given credentials. */
    FakeStsAssumeRole returning(AwsCredentials credentials) {
        this.toReturn = credentials;
        this.toThrow = null;
        return this;
    }

    /** Configure the fake to throw the given exception. */
    FakeStsAssumeRole throwing(RuntimeException exception) {
        this.toThrow = exception;
        this.toReturn = null;
        return this;
    }

    AssumeRoleRequest lastRequest() {
        return lastRequest;
    }

    @Override
    public AwsCredentials assumeRole(AssumeRoleRequest request) {
        this.lastRequest = request;
        if (toThrow != null) {
            throw toThrow;
        }
        return toReturn;
    }
}
