package io.jenkins.plugins.awsbuildsessionlogger.auth;

/**
 * Port abstraction over the STS AssumeRole operation — the single seam between the auth core and
 * whatever actually talks to AWS.
 *
 * <p>The auth core depends only on this interface, never on a concrete client. This keeps the core
 * independent of both Jenkins and the AWS transport: it can be unit-tested with a hand-written fake,
 * and the real implementation (planned to shell out to {@code aws sts assume-role} through the generic
 * executor in a later milestone, consistent with the project's CLI-first, no-SDK direction) can be
 * dropped in behind this interface without touching the core.
 */
@FunctionalInterface
public interface StsAssumeRole {

    /**
     * Assumes the requested role and returns the resulting temporary credentials.
     *
     * @param request the target role, session name, and optional duration
     * @return non-null temporary credentials
     * @throws AssumeRoleException if the operation fails
     */
    AwsCredentials assumeRole(AssumeRoleRequest request);
}
