package io.jenkins.plugins.awsbuildsessionlogger.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.awsbuildsessionlogger.config.AwsProfile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the decoration of a node's AWS configuration.
 *
 * <p>Plain JUnit, no {@code JenkinsRule}: this is a pure function, and it is the part of Managed
 * Authentication where a mistake breaks somebody's deployment rather than merely losing attribution.
 * Almost every test here is a variation on one assertion — <b>the output differs from the input by
 * added lines only</b> — because that property is what makes the feature safe.
 */
class AwsConfigOverlayTest {

    private static final String SESSION = "jk-uat-batchprocessor-180";
    private static final String SOURCE = "Ec2InstanceMetadata";

    /** A node configuration with the awkward things real ones contain. */
    private static final String REAL_WORLD = "# Managed by configuration management. Do not edit.\n"
            + "[default]\n"
            + "region = us-east-1\n"
            + "\n"
            + "[profile non_prod]\n"
            + "role_arn = arn:aws:iam::222222222222:role/terraform-assume-role\n"
            + "credential_source = Ec2InstanceMetadata\n"
            + "region = us-east-1\n"
            + "\n"
            + "[profile pinned]\n"
            + "role_arn = arn:aws:iam::111111111111:role/x\n"
            + "role_session_name = deliberately-pinned-by-an-administrator\n"
            + "\n"
            + "[sso-session corp]\n"
            + "sso_start_url = https://example.awsapps.com/start\n"
            + "\n"
            + "[profile static]\n"
            + "region = ap-south-1\n";

    // --- the governing property ----------------------------------------------

    @Test
    void theOutputDiffersFromTheInputByAddedLinesOnly() {
        List<String> before = lines(REAL_WORLD);
        List<String> after = lines(apply(REAL_WORLD));

        // Every original line must still be present, in its original order. Anything else means the
        // node's configuration was altered, which is the one thing this must never do.
        int i = 0;
        for (String original : before) {
            while (i < after.size() && !after.get(i).equals(original)) {
                assertTrue(
                        after.get(i).startsWith("role_session_name = "),
                        "only session-name lines may be inserted, found: " + after.get(i));
                i++;
            }
            assertTrue(i < after.size(), "original line was lost: " + original);
            i++;
        }
    }

    @Test
    void addsTheSessionNameToProfilesThatAssumeARole() {
        String out = apply(REAL_WORLD);

        assertEquals(1, count(out, "role_session_name = " + SESSION), out);
        assertTrue(section(out, "profile non_prod").contains("role_session_name = " + SESSION), out);
    }

    @Test
    void leavesAnAdministratorsPinnedSessionNameAlone() {
        // A pinned value is a deliberate decision. Overriding it is worse than losing attribution.
        String pinnedSection = section(apply(REAL_WORLD), "profile pinned");

        assertTrue(pinnedSection.contains("role_session_name = deliberately-pinned-by-an-administrator"));
        assertFalse(pinnedSection.contains(SESSION), pinnedSection);
    }

    @Test
    void leavesProfilesThatAssumeNoRoleAlone() {
        String out = apply(REAL_WORLD);

        assertFalse(section(out, "default").contains("role_session_name"), out);
        assertFalse(section(out, "profile static").contains("role_session_name"), out);
    }

    @Test
    void leavesSectionsItDoesNotUnderstandAlone() {
        // [sso-session], [services], and whatever AWS adds next must survive untouched. This is why
        // the transform is line-based rather than a parse and regenerate.
        assertEquals(section(REAL_WORLD, "sso-session corp"), section(apply(REAL_WORLD), "sso-session corp"));
    }

    @Test
    void preservesCommentsAndBlankLines() {
        String out = apply(REAL_WORLD);

        assertTrue(out.startsWith("# Managed by configuration management. Do not edit.\n"), out);
        assertEquals(count(REAL_WORLD, ""), count(out, ""), "blank-line count should be unchanged");
    }

    // --- shapes that must not throw or corrupt --------------------------------

    @Test
    void handlesAnEmptyConfiguration() {
        assertEquals("", apply(""));
    }

    @Test
    void handlesAConfigurationWithNoTrailingNewline() {
        String out = apply("[profile a]\nrole_arn = arn:aws:iam::1:role/r");

        assertTrue(out.endsWith("\n"), "output should be newline-terminated");
        assertTrue(out.contains("role_session_name = " + SESSION));
    }

    @Test
    void handlesWindowsLineEndings() {
        String out = apply("[profile a]\r\nrole_arn = arn:aws:iam::1:role/r\r\n");

        assertTrue(out.contains("role_session_name = " + SESSION), out);
        assertFalse(out.contains("\r"), "output should be normalised, not doubled");
    }

    @Test
    void handlesKeysWithoutSurroundingSpaces() {
        assertTrue(apply("[profile a]\nrole_arn=arn:aws:iam::1:role/r\n").contains("role_session_name = " + SESSION));
    }

    @Test
    void handlesContentBeforeTheFirstSection() {
        // Stray lines before any header belong to no profile and must simply be copied.
        String out = apply("# leading comment\n\n[profile a]\nrole_arn = arn:aws:iam::1:role/r\n");

        assertTrue(out.startsWith("# leading comment\n\n["), out);
        assertEquals(1, count(out, "role_session_name = " + SESSION));
    }

    @Test
    void insertsAfterTheLastMeaningfulLineNotAfterTrailingBlanks() {
        String out = apply("[profile a]\nrole_arn = arn:aws:iam::1:role/r\n\n\n[profile b]\nregion = x\n");

        int inserted = lines(out).indexOf("role_session_name = " + SESSION);
        assertEquals(2, inserted, "should follow role_arn directly, before the blank lines:\n" + out);
    }

    @Test
    void aSectionAppearingTwiceIsStillOnlyDecoratedWhereItAssumesARole() {
        String out = apply("[profile a]\nregion = x\n\n[profile b]\nrole_arn = arn:aws:iam::1:role/r\n");

        assertEquals(1, count(out, "role_session_name = " + SESSION), out);
    }

    // --- Jenkins-configured fallback profiles ---------------------------------

    @Test
    void appendsAProfileTheNodeDoesNotDefine() {
        String out = apply(REAL_WORLD, List.of(profile("sandbox", "arn:aws:iam::9:role/sandbox", "eu-west-1")));

        assertTrue(out.contains("[profile sandbox]"), out);
        assertTrue(out.contains("role_arn = arn:aws:iam::9:role/sandbox"), out);
        assertTrue(out.contains("region = eu-west-1"), out);
    }

    @Test
    void theNodeWinsWhenBothDefineTheSameProfile() {
        // Jenkins configuration is a fallback for what the node does not know, never an override of
        // what it does. Two [profile non_prod] sections would also be ambiguous to the AWS parser.
        String out = apply(REAL_WORLD, List.of(profile("non_prod", "arn:aws:iam::9:role/WRONG", null)));

        assertEquals(1, count(out, "[profile non_prod]"), out);
        assertFalse(out.contains("WRONG"), "the node's definition must survive intact:\n" + out);
    }

    @Test
    void appendedProfilesCarryTheSessionNameToo() {
        String out = apply("", List.of(profile("sandbox", "arn:aws:iam::9:role/sandbox", null)));

        assertTrue(out.contains("role_session_name = " + SESSION), out);
        assertTrue(out.contains("credential_source = " + SOURCE), out);
    }

    @Test
    void ignoresUnusableFallbackEntries() {
        AwsProfile noArn = new AwsProfile("broken", "");
        noArn.setMode(AwsProfile.ASSUME_ROLE);

        assertFalse(apply("", List.of(noArn)).contains("broken"));
    }

    // --- helpers --------------------------------------------------------------

    private static String apply(String nodeConfig) {
        return AwsConfigOverlay.apply(nodeConfig, SESSION, List.of(), SOURCE);
    }

    private static String apply(String nodeConfig, List<AwsProfile> overrides) {
        return AwsConfigOverlay.apply(nodeConfig, SESSION, overrides, SOURCE);
    }

    private static AwsProfile profile(String name, String roleArn, String region) {
        AwsProfile profile = new AwsProfile(name, roleArn);
        profile.setMode(AwsProfile.ASSUME_ROLE);
        profile.setRegion(region);
        return profile;
    }

    private static List<String> lines(String content) {
        return List.of(content.split("\n", -1));
    }

    private static int count(String content, String line) {
        int n = 0;
        for (String candidate : content.split("\n", -1)) {
            if (candidate.equals(line) || (!line.isEmpty() && candidate.startsWith(line))) {
                n++;
            }
        }
        return n;
    }

    /** Returns one section's body, so a test can assert about it without matching the whole file. */
    private static String section(String content, String name) {
        List<String> collected = new ArrayList<>();
        boolean inside = false;
        for (String line : content.split("\n", -1)) {
            if (line.trim().startsWith("[")) {
                inside = line.trim().equals("[" + name + "]");
                continue;
            }
            if (inside) {
                collected.add(line);
            }
        }
        return String.join("\n", collected);
    }
}
