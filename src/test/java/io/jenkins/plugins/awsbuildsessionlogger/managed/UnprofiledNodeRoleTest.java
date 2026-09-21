package io.jenkins.plugins.awsbuildsessionlogger.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.FilePath;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Selection of the unprofiled identity.
 *
 * <p>The per-node path itself needs IMDS and therefore a real EC2 node; it is verified on a real EC2
 * node rather than here. What these tests lock down is the part that decides which source is used,
 * and the fail-safe: anything that cannot be resolved must yield {@code null}, because {@code null}
 * means "write no {@code [default]} and leave unprofiled calls exactly as the node had them".
 */
@WithJenkins
class UnprofiledNodeRoleTest {

    private static final String FIXED = "arn:aws:iam::123456789012:role/fixed-role";

    /**
     * The per-node cache is static and now caches failures too, so without this the first test to record
     * an unresolvable node short-circuits every later one in the same JVM — and they would pass while
     * never running the resolution path they exist to check.
     */
    @org.junit.jupiter.api.BeforeEach
    void clearNodeRoleCache() {
        ManagedAwsContext.forgetNodeRoles();
    }

    @Test
    void usesTheConfiguredArnWhenPerNodeResolutionIsOff(JenkinsRule r, @TempDir Path tmp) throws Exception {
        AwsBuildSessionConfiguration config = AwsBuildSessionConfiguration.get();
        config.setUnprofiledRoleArn(FIXED);
        config.setAttributeUnprofiledAsNodeRole(false);

        assertEquals(
                FIXED,
                ManagedAwsContext.unprofiledRoleArnFor(config, new FilePath(tmp.toFile()), null, null),
                "with per-node resolution off, the configured ARN is used verbatim");
    }

    @Test
    void blankConfiguredArnMeansNoDefaultIsWritten(JenkinsRule r, @TempDir Path tmp) throws Exception {
        AwsBuildSessionConfiguration config = AwsBuildSessionConfiguration.get();
        config.setUnprofiledRoleArn(null);
        config.setAttributeUnprofiledAsNodeRole(false);

        assertNull(
                ManagedAwsContext.unprofiledRoleArnFor(config, new FilePath(tmp.toFile()), null, null),
                "blank means unprofiled calls are left alone");
    }

    /**
     * The controller running these tests is not an EC2 instance, so IMDS is unreachable. That is
     * exactly the fail-safe case: resolution fails, and the answer must be {@code null} rather than
     * the configured ARN — falling back to a fixed ARN on a node whose role is unknown is precisely
     * the failure this feature exists to remove.
     */
    @Test
    void perNodeResolutionFailsSafeWhenImdsIsUnreachable(JenkinsRule r, @TempDir Path tmp) throws Exception {
        AwsBuildSessionConfiguration config = AwsBuildSessionConfiguration.get();
        config.setUnprofiledRoleArn(FIXED);
        config.setAttributeUnprofiledAsNodeRole(true);

        assertNull(
                ManagedAwsContext.unprofiledRoleArnFor(config, new FilePath(tmp.toFile()), null, null),
                "an unresolvable node must yield null, never the configured ARN");
    }

    @Test
    void perNodeResolutionOverridesTheConfiguredArn(JenkinsRule r, @TempDir Path tmp) throws Exception {
        AwsBuildSessionConfiguration config = AwsBuildSessionConfiguration.get();
        config.setUnprofiledRoleArn(FIXED);
        config.setAttributeUnprofiledAsNodeRole(true);

        // Off this controller IMDS is unreachable, so the result is null - and crucially NOT the
        // configured ARN, proving the fixed value is genuinely ignored when the checkbox is on.
        assertNull(ManagedAwsContext.unprofiledRoleArnFor(config, new FilePath(tmp.toFile()), null, null));
        config.setAttributeUnprofiledAsNodeRole(false);
        assertEquals(FIXED, ManagedAwsContext.unprofiledRoleArnFor(config, new FilePath(tmp.toFile()), null, null));
    }

    @Test
    void theSettingRoundTripsThroughConfiguration(JenkinsRule r) {
        AwsBuildSessionConfiguration config = AwsBuildSessionConfiguration.get();
        config.setAttributeUnprofiledAsNodeRole(true);
        assertEquals(true, AwsBuildSessionConfiguration.get().isAttributeUnprofiledAsNodeRole());
        config.setAttributeUnprofiledAsNodeRole(false);
        assertEquals(false, AwsBuildSessionConfiguration.get().isAttributeUnprofiledAsNodeRole());
    }
}
