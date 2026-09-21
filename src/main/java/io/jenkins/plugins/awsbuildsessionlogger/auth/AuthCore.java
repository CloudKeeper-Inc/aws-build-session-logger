package io.jenkins.plugins.awsbuildsessionlogger.auth;

import java.util.Objects;

/**
 * The authentication core: turns a target role plus a Jenkins job/build identity into temporary AWS
 * credentials, via the {@link StsAssumeRole} port.
 *
 * <p>Deliberately Jenkins- and CLI-agnostic. Inputs are plain values ({@code roleArn}, {@code jobName},
 * {@code buildNumber}) — the future Jenkins layer is responsible for extracting them from a build and
 * for convention-based profile-&gt;role resolution. This class only: generates the load-bearing
 * {@code jk-<job>-<build>} session name, builds the request, delegates to the port, and normalizes
 * failures into an actionable {@link AssumeRoleException} (fail closed, never guess).
 *
 * <p>Stateless: every call performs an AssumeRole. Caching and refresh are out of scope
 * and belong to a future {@link CredentialsProvider} decorator.
 */
public final class AuthCore {

    private final StsAssumeRole sts;

    public AuthCore(StsAssumeRole sts) {
        this.sts = Objects.requireNonNull(sts, "sts");
    }

    /**
     * Authenticates by assuming {@code roleArn} with a {@code jk-<job>-<build>} session name.
     *
     * @param roleArn the target role ARN
     * @param jobName the Jenkins job name
     * @param buildNumber the Jenkins build number (positive)
     * @return non-null temporary credentials
     * @throws SessionNameException if a valid session name cannot be formed from the inputs
     * @throws AssumeRoleException if the AssumeRole operation fails or returns no credentials
     */
    public AwsCredentials authenticate(String roleArn, String jobName, long buildNumber) {
        SessionName sessionName = SessionName.forBuild(jobName, buildNumber);
        AssumeRoleRequest request = AssumeRoleRequest.of(roleArn, sessionName);
        return assume(request);
    }

    private AwsCredentials assume(AssumeRoleRequest request) {
        try {
            AwsCredentials credentials = sts.assumeRole(request);
            if (credentials == null) {
                throw new AssumeRoleException("AssumeRole for role " + request.roleArn() + " as session "
                        + request.sessionName().value() + " returned no credentials.");
            }
            return credentials;
        } catch (AssumeRoleException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AssumeRoleException(
                    "Failed to assume role " + request.roleArn() + " as session "
                            + request.sessionName().value() + ": " + e.getMessage(),
                    e);
        }
    }
}
