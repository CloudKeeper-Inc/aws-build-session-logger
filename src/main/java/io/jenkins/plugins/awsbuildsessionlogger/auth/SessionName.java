package io.jenkins.plugins.awsbuildsessionlogger.auth;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The STS {@code RoleSessionName} for one Jenkins build, in the frozen {@code jk-<job>-<build>} shape.
 *
 * <p>This shape is <b>load-bearing</b>: it is the basis for a future IAM trust-policy condition
 * ({@code "StringLike": {"sts:RoleSessionName": "jk-*"}}). Do not change the {@code jk-} prefix or the
 * overall shape without discussion — see {@code docs/DECISION-LOG.md}.
 *
 * <p>AWS constrains {@code RoleSessionName} to the pattern {@code [\w+=,.@-]{2,64}}. Job names can
 * contain characters outside that set (slashes for folders, spaces, unicode), so this class sanitizes
 * the job segment while always preserving the {@code jk-} prefix and the trailing build number, and
 * truncates the <i>middle</i> (job) segment if needed so the result stays {@code <= 64} chars and keeps
 * matching {@code jk-*}.
 */
public final class SessionName {

    /** Length bounds AWS enforces on a RoleSessionName. */
    private static final int MAX_LENGTH = 64;

    private static final int MIN_LENGTH = 2;

    /** The fixed, load-bearing prefix. */
    private static final String PREFIX = "jk-";

    /** Characters AWS permits in a RoleSessionName; anything else in the job segment is replaced. */
    private static final Pattern DISALLOWED = Pattern.compile("[^\\w+=,.@-]");

    /** Collapses runs of '-' left behind by sanitization into a single '-'. */
    private static final Pattern DASH_RUN = Pattern.compile("-{2,}");

    /** Full-value validity check, applied defensively to the assembled result. */
    private static final Pattern VALID = Pattern.compile("[\\w+=,.@-]{" + MIN_LENGTH + "," + MAX_LENGTH + "}");

    private final String value;

    private SessionName(String value) {
        this.value = value;
    }

    /**
     * Builds the session name for a build.
     *
     * @param jobName the Jenkins job name (may contain folder slashes, spaces, etc.)
     * @param buildNumber the build number; must be positive
     * @return a valid, STS-safe session name of the form {@code jk-<sanitized-job>-<build>}
     * @throws SessionNameException if {@code jobName} is blank, {@code buildNumber} is not positive, or
     *     a valid name cannot be produced (e.g. an implausibly large build number leaves no room)
     */
    public static SessionName forBuild(String jobName, long buildNumber) {
        if (jobName == null || jobName.trim().isEmpty()) {
            throw new SessionNameException("Job name must not be null or blank when generating a session name.");
        }
        if (buildNumber <= 0) {
            throw new SessionNameException("Build number must be positive but was " + buildNumber + ".");
        }

        String suffix = "-" + buildNumber;
        int roomForJob = MAX_LENGTH - PREFIX.length() - suffix.length();
        if (roomForJob <= 0) {
            // Only reachable with an absurd build number; fail closed rather than emit a broken name.
            throw new SessionNameException("Build number " + buildNumber
                    + " is too large to form a session name within " + MAX_LENGTH + " characters.");
        }

        String job = sanitizeJobSegment(jobName);

        // Truncating or sanitizing away the job name destroys the very thing the session name exists to
        // carry, and two different jobs can then produce the SAME name — at which point CloudTrail
        // attributes both builds to one identity and the audit silently lies. Keeping the tail helps
        // (leading folder segments are shared; the trailing segment distinguishes) but does not fix it:
        // "platform/a/deploy-service" and "platform/b/deploy-service" share their tail. So whenever the
        // name is not carried intact, a short digest OF THE FULL ORIGINAL is appended to restore
        // uniqueness. Names that fit are untouched, which is the overwhelming majority.
        if (job.length() > roomForJob || job.isEmpty()) {
            String digest = shortDigest(jobName);
            int roomLeft = roomForJob - digest.length() - 1; // -1 for the dash joining head to digest
            String head = roomLeft <= 0 ? "" : trimDashes(job.substring(Math.max(0, job.length() - roomLeft)));
            job = head.isEmpty() ? digest : head + "-" + digest;
        }

        String value = PREFIX + job + suffix;

        if (!VALID.matcher(value).matches()) {
            throw new SessionNameException("Generated session name is not STS-valid: '" + value + "'.");
        }
        return new SessionName(value);
    }

    private static String sanitizeJobSegment(String jobName) {
        String replaced = DISALLOWED.matcher(jobName).replaceAll("-");
        return trimDashes(DASH_RUN.matcher(replaced).replaceAll("-"));
    }

    /**
     * Six lowercase hex characters of SHA-256 over the untouched job name.
     *
     * <p>Not a security property — purely a collision breaker, so it need only be short and stable. It is
     * computed from the ORIGINAL name rather than the sanitized one, so that two names which sanitize
     * alike still differ <em>once a digest is appended at all</em>.
     *
     * <p><b>Known and accepted gap.</b> A digest is appended only when the name is truncated or
     * sanitizes to nothing. Names that sanitize lossily but still <em>fit</em> get no digest, so
     * {@code a/b}, {@code a-b} and {@code a b} all remain {@code jk-a-b-<build>} — as do a folder job
     * {@code platform/deploy} and a top-level {@code platform-deploy}. Closing it would mean appending a
     * digest to every folder job, changing the session name of almost every build on the controller and
     * breaking CloudTrail name continuity. That is a rollout decision, not a patch, and it is
     * deliberately not taken here.
     */
    private static String shortDigest(String jobName) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(jobName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(6);
            for (int i = 0; i < 3; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is genuinely absent, a stable fallback still
            // distinguishes names better than dropping them.
            return String.format("%06x", jobName.hashCode() & 0xffffff);
        }
    }

    private static String trimDashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(start, end);
    }

    /** The session-name string to pass to STS. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionName)) {
            return false;
        }
        return value.equals(((SessionName) o).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
