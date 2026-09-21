package io.jenkins.plugins.awsbuildsessionlogger.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The two agent configuration shapes that actually exist in production, as fixtures.
 *
 * <p>These exist because of a real incident. An earlier investigation reproduced against a shape that
 * does not occur in the fleet — no {@code [default]} section, two profiles — concluded from it that the
 * overlay could not affect an unprofiled consumer, and closed the leading hypothesis on that basis. The
 * measured reality was a {@code [default]} section plus six profiles, one of which assumes no role. A
 * negative result against the wrong shape is not a negative result.
 *
 * <p>Account IDs here are placeholders. The repository is public; the real inventory is not in it.
 *
 * <ul>
 *   <li>{@link #STANDARD} — carried by six of seven agents, byte-identical between them: a
 *       {@code [default]} with no credentials, five role-assuming profiles, and one profile that
 *       assumes nothing.
 *   <li>{@link #MINIMAL} — the seventh agent: a single profile and <b>no {@code [default]} at all</b>.
 *       Any code that assumes a default section exists fails here and nowhere else.
 * </ul>
 */
class ProductionShapeFixturesTest {

    private static final String SESSION = "jk-dev-service-a-31";
    private static final String SOURCE = "Ec2InstanceMetadata";
    private static final String SELF_ROLE = "arn:aws:iam::111111111111:role/agent-instance-role";

    private static final String STANDARD = "[default]\n"
            + "output = json\n"
            + "region = us-east-1\n"
            + "[profile non_prod]\n"
            + "role_arn = arn:aws:iam::222222222222:role/terraform-assume-role\n"
            + "region = us-east-1\n"
            + "credential_source = Ec2InstanceMetadata\n"
            + "[profile prod]\n"
            + "role_arn = arn:aws:iam::333333333333:role/terraform-assume-role\n"
            + "credential_source = Ec2InstanceMetadata\n"
            + "region = us-east-1\n"
            + "[profile ops]\n"
            + "credential_source = Ec2InstanceMetadata\n"
            + "region = us-east-1\n"
            + "[profile multi-tenant]\n"
            + "role_arn = arn:aws:iam::444444444444:role/cross-account-access\n"
            + "credential_source = Ec2InstanceMetadata\n"
            + "region = us-east-1\n"
            + "[profile tenant_b_non_prod]\n"
            + "role_arn = arn:aws:iam::555555555555:role/tenant-b-terraform-role\n"
            + "credential_source = Ec2InstanceMetadata\n"
            + "region = us-east-1\n"
            + "[profile tenant_b_prod]\n"
            + "role_arn = arn:aws:iam::666666666666:role/tenant-b-terraform-role\n"
            + "credential_source = Ec2InstanceMetadata\n"
            + "region = us-east-1\n";

    private static final String MINIMAL = "[profile website]\n"
            + "role_arn = arn:aws:iam::777777777777:role/terraform-assume-role\n"
            + "credential_source = Ec2InstanceMetadata\n"
            + "region = us-east-1\n";

    // --- what the standard shape actually contains ---------------------------

    @Test
    void theStandardShapeHasSevenSectionsNotTwo() {
        AwsConfigOverlay.Result result = describe(STANDARD, null);

        assertEquals(
                List.of(
                        "default",
                        "profile non_prod",
                        "profile prod",
                        "profile ops",
                        "profile multi-tenant",
                        "profile tenant_b_non_prod",
                        "profile tenant_b_prod"),
                result.sectionsFound(),
                "the fixture must mirror the measured production shape");
    }

    @Test
    void onlyRoleAssumingProfilesAreDecorated() {
        AwsConfigOverlay.Result result = describe(STANDARD, null);

        assertEquals(
                List.of(
                        "profile non_prod",
                        "profile prod",
                        "profile multi-tenant",
                        "profile tenant_b_non_prod",
                        "profile tenant_b_prod"),
                result.sectionsDecorated(),
                "'ops' assumes no role and '[default]' has no credentials: neither can carry a session name");
    }

    /**
     * The precise claim the earlier investigation made, now pinned. Without an unprofiled role the
     * plugin cannot change what a consumer naming no profile receives — but this is only true because
     * {@code [default]} is left alone, which is exactly what needed testing against this shape.
     */
    @Test
    void withoutAnUnprofiledRoleTheDefaultSectionIsUntouched() {
        String generated = describe(STANDARD, null).content();

        int defaultStart = generated.indexOf("[default]");
        int nextSection = generated.indexOf("[profile non_prod]");
        String defaultSection = generated.substring(defaultStart, nextSection);

        assertEquals("[default]\noutput = json\nregion = us-east-1\n", defaultSection);
        assertFalse(describe(STANDARD, null).unprofiledAttributed());
    }

    // --- attributing the unprofiled path -------------------------------------

    @Test
    void anUnprofiledRoleTurnsTheDefaultSectionIntoAnAssumeRole() {
        AwsConfigOverlay.Result result = describe(STANDARD, SELF_ROLE);
        String generated = result.content();

        int defaultStart = generated.indexOf("[default]");
        String defaultSection = generated.substring(defaultStart, generated.indexOf("[profile non_prod]"));

        assertTrue(defaultSection.contains("role_arn = " + SELF_ROLE), "the role must be assumed");
        assertTrue(defaultSection.contains("credential_source = " + SOURCE), "the base identity must be named");
        assertTrue(defaultSection.contains("role_session_name = " + SESSION), "the build must be identifiable");
        assertTrue(defaultSection.contains("output = json"), "the node's own keys must survive");
        assertTrue(defaultSection.contains("region = us-east-1"), "the node's own keys must survive");
        assertTrue(result.unprofiledAttributed());
        assertTrue(result.sectionsDecorated().contains("default"));
    }

    /**
     * A named profile with no {@code role_arn} is the same unattributable path as {@code [default]},
     * reached by name instead of by omission: it hands the build the agent's base identity directly,
     * whose session name the platform fixed. {@code aws --profile ops …} must therefore be attributed
     * too, or a caller could opt out of the audit simply by naming that profile.
     */
    @Test
    void aNamedProfileWithNoRoleIsAttributedLikeTheDefaultSection() {
        AwsConfigOverlay.Result result = describe(STANDARD, SELF_ROLE);
        String generated = result.content();

        String ops =
                generated.substring(generated.indexOf("[profile ops]"), generated.indexOf("[profile multi-tenant]"));

        assertTrue(ops.contains("role_arn = " + SELF_ROLE), "ops must assume the agent's own role");
        assertTrue(ops.contains("role_session_name = " + SESSION), "and carry the build's session name");
        assertTrue(ops.contains("credential_source = " + SOURCE), "the node's own key is kept");
        assertTrue(result.sectionsDecorated().contains("profile ops"));
        assertAdditionsOnly(STANDARD, generated);
    }

    /**
     * A key the section already declares is never written a second time.
     *
     * <p>Regression test for a production failure. {@code [profile ops]} carries
     * {@code credential_source} but no {@code role_arn}; it was given the full assume-role triple,
     * duplicating that key. botocore then refused to parse the <b>entire file</b> — so every profile in
     * it failed, not just {@code ops}, and every AWS call in the build failed with
     * <em>"Unable to parse config file"</em>. Worse than losing attribution, which is the one outcome
     * this must never cause.
     */
    @Test
    void aKeyTheSectionAlreadyDeclaresIsNeverWrittenTwice() {
        String generated = describe(STANDARD, SELF_ROLE).content();

        String ops =
                generated.substring(generated.indexOf("[profile ops]"), generated.indexOf("[profile multi-tenant]"));

        assertEquals(
                1,
                ops.split("credential_source", -1).length - 1,
                "credential_source must appear exactly once in [profile ops]:\n" + ops);
        assertTrue(ops.contains("role_arn = " + SELF_ROLE), "the role is still added");
        assertTrue(ops.contains("role_session_name = " + SESSION), "and so is the session name");
        assertEquals(
                Optional.empty(),
                AwsConfigOverlay.validate(STANDARD, generated),
                "and the result must pass the safety check");
    }

    /** The safety check must catch a duplicate key even though it is, structurally, an addition. */
    @Test
    void theSafetyCheckRefusesADuplicateKey() {
        String duplicated = "[profile ops]\n"
                + "credential_source = Ec2InstanceMetadata\n"
                + "region = us-east-1\n"
                + "role_arn = " + SELF_ROLE + "\n"
                + "credential_source = Ec2InstanceMetadata\n"
                + "role_session_name = " + SESSION + "\n";
        String original = "[profile ops]\ncredential_source = Ec2InstanceMetadata\nregion = us-east-1\n";

        Optional<String> defect = AwsConfigOverlay.validate(original, duplicated);

        assertTrue(defect.isPresent(), "a duplicated key must be refused");
        assertTrue(defect.get().contains("credential_source"), defect.get());
        assertTrue(defect.get().contains("twice"), defect.get());
    }

    /** Without an unprofiled role configured, a no-role profile is still left completely alone. */
    @Test
    void aNamedProfileWithNoRoleIsUntouchedWhenNoUnprofiledRoleIsConfigured() {
        AwsConfigOverlay.Result result = describe(STANDARD, null);

        assertFalse(result.sectionsDecorated().contains("profile ops"));
        String generated = result.content();
        String ops =
                generated.substring(generated.indexOf("[profile ops]"), generated.indexOf("[profile multi-tenant]"));
        assertEquals("[profile ops]\ncredential_source = Ec2InstanceMetadata\nregion = us-east-1\n", ops);
    }

    /**
     * Sections that are not profiles must never be given a role. A shared configuration file also holds
     * {@code [sso-session x]}, {@code [services x]} and similar; writing {@code role_arn} into one would
     * corrupt it.
     */
    @Test
    void nonProfileSectionsAreNeverGivenARole() {
        String withSsoSession = "[sso-session corp]\n"
                + "sso_start_url = https://example.awsapps.com/start\n"
                + "[services custom]\n"
                + "s3 =\n"
                + "[profile plain]\n"
                + "region = us-east-1\n";

        AwsConfigOverlay.Result result = describe(withSsoSession, SELF_ROLE);
        String generated = result.content();

        assertFalse(
                generated
                        .substring(generated.indexOf("[sso-session corp]"), generated.indexOf("[services custom]"))
                        .contains("role_arn"),
                "an sso-session is not a profile");
        assertFalse(
                generated
                        .substring(generated.indexOf("[services custom]"), generated.indexOf("[profile plain]"))
                        .contains("role_arn"),
                "a services block is not a profile");
        assertTrue(
                generated.substring(generated.indexOf("[profile plain]")).contains("role_arn = " + SELF_ROLE),
                "but a profile with no role is");
    }

    @Test
    void attributingTheUnprofiledPathStillOnlyAddsLines() {
        assertAdditionsOnly(STANDARD, describe(STANDARD, SELF_ROLE).content());
    }

    @Test
    void theMinimalShapeGainsADefaultSectionBecauseItHasNone() {
        AwsConfigOverlay.Result result = describe(MINIMAL, SELF_ROLE);

        assertFalse(result.sectionsFound().contains("default"), "this agent defines no default section");
        assertTrue(result.sectionsAppended().contains("default"), "so one has to be added to reach it");
        assertTrue(result.unprofiledAttributed());
        assertTrue(result.content().contains("role_arn = " + SELF_ROLE));
        assertAdditionsOnly(MINIMAL, result.content());
    }

    @Test
    void theMinimalShapeIsLeftAloneWhenNoUnprofiledRoleIsConfigured() {
        AwsConfigOverlay.Result result = describe(MINIMAL, null);

        assertTrue(result.sectionsAppended().isEmpty(), "nothing may be invented without configuration");
        assertFalse(result.content().contains("[default]"));
        assertEquals(List.of("profile website"), result.sectionsDecorated());
    }

    // --- the safety check ----------------------------------------------------

    @Test
    void bothShapesPassTheSafetyCheckInEveryMode() {
        for (String shape : List.of(STANDARD, MINIMAL)) {
            for (String role : new String[] {null, SELF_ROLE}) {
                assertEquals(
                        Optional.empty(),
                        AwsConfigOverlay.validate(shape, describe(shape, role).content()),
                        "a correctly generated configuration must be considered safe");
            }
        }
    }

    @Test
    void theSafetyCheckRefusesOutputThatLostALine() {
        String generated = describe(STANDARD, SELF_ROLE).content().replace("output = json\n", "");

        Optional<String> defect = AwsConfigOverlay.validate(STANDARD, generated);

        assertTrue(defect.isPresent(), "a dropped line must be caught before the build sees the file");
        assertTrue(defect.get().contains("output = json"), "and the reason must name it: " + defect.get());
    }

    @Test
    void theSafetyCheckRefusesOutputThatLostASection() {
        String generated = describe(STANDARD, null)
                .content()
                .replace("[profile tenant_b_prod]\n", "")
                .replace("role_arn = arn:aws:iam::666666666666:role/tenant-b-terraform-role\n", "");

        assertTrue(
                AwsConfigOverlay.validate(STANDARD, generated).isPresent(),
                "a dropped section must be caught before the build sees the file");
    }

    /**
     * The controller's shape: blank lines between sections, and a trailing blank line at the end of a
     * file whose last section assumes a role.
     *
     * <p>This is a regression test for a real false positive. Joining lines with {@code "\n"} turns a
     * final empty element into a single terminating newline, so a file ending in a blank line loses it
     * whenever its last section is decorated — and the safety check then reported a missing line
     * {@code ""} and suppressed decoration entirely. The check failed safe, which is the right
     * behaviour for a real defect, but this difference is not one: trailing blank lines are not content.
     * Found on a real controller, not by a test.
     */
    @Test
    void aConfigurationEndingInABlankLinePassesTheSafetyCheck() {
        String endsWithBlankLine = "[profile a]\n"
                + "role_arn = arn:aws:iam::111111111111:role/a\n"
                + "credential_source = Ec2InstanceMetadata\n"
                + "\n"
                + "[profile b]\n"
                + "role_arn = arn:aws:iam::222222222222:role/b\n"
                + "credential_source = Ec2InstanceMetadata\n"
                + "\n";

        AwsConfigOverlay.Result result = describe(endsWithBlankLine, null);

        assertEquals(
                Optional.empty(),
                AwsConfigOverlay.validate(endsWithBlankLine, result.content()),
                "a trailing blank line is not content and must not suppress decoration");
        assertEquals(List.of("profile a", "profile b"), result.sectionsDecorated());
        assertTrue(result.content().contains("role_session_name = " + SESSION));
    }

    @Test
    void aConfigurationEndingInABlankLineIsStillCheckedForRealDefects() {
        String endsWithBlankLine = "[profile a]\n" + "role_arn = arn:aws:iam::111111111111:role/a\n" + "\n";
        String corrupted = "[profile a]\n" + "role_session_name = " + SESSION + "\n";

        assertTrue(
                AwsConfigOverlay.validate(endsWithBlankLine, corrupted).isPresent(),
                "ignoring trailing blanks must not weaken the check for genuinely lost lines");
    }

    @Test
    void theSafetyCheckRefusesReorderedOutput() {
        String generated = "[profile prod]\n" + "[default]\n" + "output = json\n" + "region = us-east-1\n";

        assertTrue(
                AwsConfigOverlay.validate(STANDARD, generated).isPresent(),
                "reordering is not an addition, and must be refused");
    }

    // --- helpers -------------------------------------------------------------

    private static AwsConfigOverlay.Result describe(String nodeConfig, String unprofiledRoleArn) {
        return AwsConfigOverlay.describe(nodeConfig, SESSION, List.of(), SOURCE, unprofiledRoleArn);
    }

    /** The governing property: every original line survives, in order, and only additions appear. */
    private static void assertAdditionsOnly(String before, String after) {
        List<String> originals = List.of(before.split("\n", -1));
        List<String> produced = List.of(after.split("\n", -1));

        int cursor = 0;
        for (String original : originals) {
            boolean found = false;
            while (cursor < produced.size()) {
                if (produced.get(cursor++).equals(original)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "line lost or reordered: \"" + original + "\"");
        }
    }
}
