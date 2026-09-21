package io.jenkins.plugins.awsbuildsessionlogger.auth.cli;

import io.jenkins.plugins.awsbuildsessionlogger.auth.AssumeRoleException;
import io.jenkins.plugins.awsbuildsessionlogger.auth.AssumeRoleRequest;
import io.jenkins.plugins.awsbuildsessionlogger.auth.AwsCredentials;
import io.jenkins.plugins.awsbuildsessionlogger.auth.StsAssumeRole;
import io.jenkins.plugins.awsbuildsessionlogger.exec.ProcessExecutionException;
import io.jenkins.plugins.awsbuildsessionlogger.exec.ProcessResult;
import io.jenkins.plugins.awsbuildsessionlogger.exec.ProcessRunner;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link StsAssumeRole} implementation that shells out to the AWS CLI's {@code sts assume-role}.
 *
 * <p>This is the <em>only</em> class that knows the shape of the {@code sts assume-role} command; the
 * underlying {@link ProcessRunner} stays completely generic. Credentials are extracted with the CLI's
 * own {@code --query ... --output text} projection, so no JSON parser dependency is needed.
 *
 * <p>It is also the boundary that keeps the rest of the project transport-agnostic: every execution
 * failure — a process that cannot start ({@link ProcessExecutionException}), a non-zero exit, or
 * unparseable output — is translated into an {@link AssumeRoleException}. Nothing outside this class
 * learns that authentication happens via a subprocess.
 */
public final class CliStsAssumeRole implements StsAssumeRole {

    /** Upper bound on subprocess stderr echoed into an exception message. */
    private static final int MAX_STDERR = 2000;

    /**
     * Credential-shaped fragments in debug output: signing headers, tokens, secret keys.
     *
     * <p>The key may be <b>quoted</b> and the value may carry a Python bytes prefix, because the most
     * likely source of such output is botocore printing request headers as a dict:
     *
     * <pre>headers={'X-Amz-Security-Token': b'IQoJb3JpZ2luX2VjE…', 'Authorization': b'AWS4-HMAC-SHA256 Credential=ASIA…'}</pre>
     *
     * <p>An earlier version required {@code name} to be followed directly by {@code [=:]}, so the
     * apostrophe between them meant <em>nothing matched</em> and the session token was echoed verbatim —
     * the exact disclosure this exists to prevent. The value is consumed to a structural delimiter
     * rather than to whitespace, because {@code Authorization} values contain spaces and stopping at the
     * first one left the access key ID exposed.
     *
     * <p>Deliberately broad: over-masking a diagnostic is harmless, under-masking is a disclosure.
     */
    private static final java.util.regex.Pattern SENSITIVE = java.util.regex.Pattern.compile(
            "(?i)[\"']?(authorization|x-amz-security-token|aws_secret_access_key|aws_session_token"
                    + "|aws_access_key_id|secretaccesskey|sessiontoken|accesskeyid|credential|signature)"
                    + "[\"']?\\s*[=:]\\s*b?[\"']?[^\\s,'\"}\\]]+[^,'\"}\\]]*");

    private static final String DEFAULT_AWS_EXECUTABLE = "aws";

    /** Projects exactly the four credential fields, in order, as tab-separated text. */
    private static final String CREDENTIALS_QUERY = "Credentials.[AccessKeyId,SecretAccessKey,SessionToken,Expiration]";

    private static final int EXPECTED_FIELDS = 4;

    private final ProcessRunner runner;
    private final String awsExecutable;

    public CliStsAssumeRole(ProcessRunner runner) {
        this(runner, DEFAULT_AWS_EXECUTABLE);
    }

    public CliStsAssumeRole(ProcessRunner runner, String awsExecutable) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.awsExecutable = requireNonBlank(awsExecutable, "awsExecutable");
    }

    @Override
    public AwsCredentials assumeRole(AssumeRoleRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> command = buildCommand(request);

        ProcessResult result;
        try {
            result = runner.run(command);
        } catch (ProcessExecutionException e) {
            // Map the execution-layer failure so callers only ever see AssumeRoleException.
            throw new AssumeRoleException(
                    context("could not execute 'aws sts assume-role'", request) + ": " + e.getMessage(), e);
        }

        if (!result.succeeded()) {
            throw new AssumeRoleException(context("'aws sts assume-role' failed", request) + " (exit "
                    + result.exitCode() + "): " + safeStderr(result.stderr()));
        }

        return parseCredentials(result.stdout(), request);
    }

    /**
     * The child's stderr, truncated and stripped of anything credential-shaped.
     *
     * <p>This text is embedded in an exception that the step prints to the build console — and the
     * secret-masking filter is attached only to the step's <em>body</em>, so it is never in effect on
     * this path. With {@code AWS_DEBUG} or {@code debug = true} in the agent's configuration, a failed
     * assume-role writes the signed {@code Authorization} header and the SOURCE session token to stderr,
     * which would then land verbatim in a log readable by anyone with Job/Read.
     */
    private static String safeStderr(String stderr) {
        if (stderr == null) {
            return "";
        }
        String cleaned = SENSITIVE.matcher(stderr.trim()).replaceAll("$1=<redacted>");
        return cleaned.length() <= MAX_STDERR ? cleaned : cleaned.substring(0, MAX_STDERR) + "... (truncated)";
    }

    private List<String> buildCommand(AssumeRoleRequest request) {
        List<String> command = new ArrayList<>();
        command.add(awsExecutable);
        command.add("sts");
        command.add("assume-role");
        command.add("--role-arn");
        command.add(request.roleArn());
        command.add("--role-session-name");
        command.add(request.sessionName().value());
        request.durationSeconds().ifPresent(seconds -> {
            command.add("--duration-seconds");
            command.add(Integer.toString(seconds));
        });
        command.add("--query");
        command.add(CREDENTIALS_QUERY);
        command.add("--output");
        command.add("text");
        return command;
    }

    private AwsCredentials parseCredentials(String stdout, AssumeRoleRequest request) {
        String line = stdout.trim();
        if (line.isEmpty()) {
            throw new AssumeRoleException(context("'aws sts assume-role' returned no output", request));
        }
        String[] fields = line.split("\t", -1);
        if (fields.length != EXPECTED_FIELDS) {
            throw new AssumeRoleException(context("unexpected 'aws sts assume-role' output", request) + ": expected "
                    + EXPECTED_FIELDS + " tab-separated fields but got " + fields.length);
        }
        Instant expiration = parseExpiration(fields[3], request);
        try {
            return new AwsCredentials(fields[0], fields[1], fields[2], expiration);
        } catch (RuntimeException e) {
            throw new AssumeRoleException(
                    context("could not build credentials from 'aws sts assume-role' output", request), e);
        }
    }

    private Instant parseExpiration(String value, AssumeRoleRequest request) {
        // The AWS CLI emits offset form (e.g. 2026-07-24T13:00:00+00:00); tolerate a plain 'Z' too.
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException offsetFailure) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException instantFailure) {
                throw new AssumeRoleException(
                        context("could not parse credential expiration '" + value + "'", request), offsetFailure);
            }
        }
    }

    private static String context(String what, AssumeRoleRequest request) {
        return what + " for role " + request.roleArn() + " as session "
                + request.sessionName().value();
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be null or blank.");
        }
        return value;
    }
}
