package io.jenkins.plugins.awsbuildsessionlogger.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Profiles that resolve credentials some way other than the agent's base identity must be left alone.
 *
 * <p>"Declares no {@code role_arn}" was treated as "uses the agent's base identity". That is false for
 * five common shapes, and each fails differently — one of them <em>silently</em>, as the wrong
 * principal, which is the worst outcome this plugin can produce:
 *
 * <ul>
 *   <li><b>SSO</b> — botocore's assume-role provider outranks the SSO provider, so the profile would
 *       authenticate as the agent's instance role in the wrong account. It succeeds; it is just no
 *       longer the identity the pipeline asked for.
 *   <li><b>{@code source_profile}</b> — botocore rejects a profile carrying both {@code source_profile}
 *       and {@code credential_source} outright, so every call using it fails.
 *   <li><b>{@code credential_process}, static keys, web identity</b> — the configured identity is
 *       replaced by the node role.
 * </ul>
 *
 * <p>None is caught by the duplicate-key guard, because the keys differ from the ones being written.
 */
class IdentityBearingProfilesTest {

    private static final String SESSION = "jk-some-job-7";
    private static final String SOURCE = "Ec2InstanceMetadata";
    private static final String SELF_ROLE = "arn:aws:iam::111111111111:role/agent-instance-role";

    private static AwsConfigOverlay.Result describe(String nodeConfig) {
        return AwsConfigOverlay.describe(nodeConfig, SESSION, List.of(), SOURCE, SELF_ROLE);
    }

    /**
     * Asserts the named profile is left verbatim.
     *
     * <p>Checked via {@code sectionsDecorated()} rather than by searching the whole file for the role
     * ARN: when a node config carries no {@code [default]}, the plugin legitimately appends one that
     * assumes exactly this ARN, so a whole-file search reports that appended section and says nothing
     * about the profile under test.
     */
    private static void assertUntouched(String config, String profile, String reason) {
        assertFalse(describe(config).sectionsDecorated().contains(profile), reason);
    }

    @Test
    void ssoProfilesAreLeftAlone() {
        assertUntouched(
                "[profile sso-dev]\n" + "sso_session = corp\n"
                        + "sso_account_id = 222222222222\n"
                        + "sso_role_name = Developer\n"
                        + "region = us-east-1\n",
                "profile sso-dev",
                "an SSO profile given role_arn would authenticate as the node role in the wrong account");
    }

    @Test
    void sourceProfileChainsAreLeftAlone() {
        assertUntouched(
                "[profile chained]\n" + "source_profile = base\n" + "region = us-east-1\n",
                "profile chained",
                "source_profile plus credential_source is rejected by botocore, failing every call");
    }

    @Test
    void credentialProcessProfilesAreLeftAlone() {
        assertUntouched(
                "[profile external]\n" + "credential_process = /usr/local/bin/creds\n",
                "profile external",
                "a credential_process profile must keep its own identity");
    }

    @Test
    void staticKeyProfilesAreLeftAlone() {
        assertUntouched(
                "[profile legacy]\n" + "aws_access_key_id = AKIAEXAMPLE\n" + "aws_secret_access_key = placeholder\n",
                "profile legacy",
                "a profile with static keys must keep its own identity");
    }

    /**
     * No {@code role_session_name} here on purpose. With it, {@code pinned} is set and {@code emissionFor}
     * returns before the identity guard is ever consulted — the test then passes with the guard reverted,
     * which is no test at all.
     */
    @Test
    void webIdentityProfilesAreLeftAlone() {
        assertUntouched(
                "[profile irsa]\n" + "web_identity_token_file = /var/run/token\n",
                "profile irsa",
                "a web-identity profile must keep its own identity");
    }

    /**
     * configparser's default delimiters are {@code ('=', ':')} and botocore does not override them, so a
     * colon-delimited SSO profile is a real SSO profile. Matching only {@code =} made it invisible to the
     * identity guard, and the build would have authenticated as the node role in the wrong account.
     */
    @Test
    void colonDelimitedIdentityKeysAreRecognised() {
        assertUntouched(
                "[profile sso-colon]\n" + "sso_session: corp\n" + "sso_account_id: 222222222222\n",
                "profile sso-colon",
                "a colon-delimited SSO profile must be recognised as identity-bearing");
    }

    /**
     * A uniformly indented profile is valid configparser — an indented line is a continuation only when
     * its indent exceeds the previous option's. Treating all indentation as continuation hid the
     * {@code role_arn} here, so the section looked like it assumed nothing and was given the triple,
     * making the file declare {@code role_arn} twice: {@code DuplicateOptionError}, and every AWS call in
     * the build fails, not merely this profile's.
     */
    @Test
    void uniformlyIndentedProfilesAreParsedNotTreatedAsContinuations() {
        // A [default] is present so none is appended; the node role may then legitimately appear exactly
        // once, in [default]. A second occurrence means it was also written into [profile ops] — which is
        // the corruption: that section already declares role_arn, just indented.
        String indented = "[default]\n"
                + "region = us-east-1\n"
                + "[profile ops]\n"
                + "    role_arn = arn:aws:iam::222222222222:role/ops\n"
                + "    credential_source = Ec2InstanceMetadata\n";

        String out = describe(indented).content();
        assertEquals(
                1,
                out.split(java.util.regex.Pattern.quote(SELF_ROLE), -1).length - 1,
                "the indented role_arn must be seen, so the assume-role triple is not added on top of it");
        assertTrue(
                AwsConfigOverlay.validate(indented, out).isEmpty(),
                "and the result must not declare role_arn twice in one section");
    }

    /** Tab indentation behaves identically to spaces in configparser, so it must here too. */
    @Test
    void tabIndentedProfilesAreParsedToo() {
        String tabbed = "[profile ops]\n\trole_arn = arn:aws:iam::222222222222:role/ops\n";
        assertTrue(
                AwsConfigOverlay.validate(tabbed, describe(tabbed).content()).isEmpty(),
                "tab-indented keys are keys, not continuations");
    }

    /**
     * The regression guard for the fix: a profile that genuinely has no other source of identity is
     * still attributed. Without this, "leave identity-bearing profiles alone" could be satisfied by
     * leaving everything alone, which would silently remove the feature.
     */
    @Test
    void aProfileWithNoOtherIdentityIsStillAttributed() {
        String out = describe("[profile plain]\n" + "region = us-east-1\n" + "output = json\n")
                .content();
        assertTrue(out.contains("role_arn = " + SELF_ROLE), "a genuinely unprofiled-equivalent section is attributed");
        assertTrue(out.contains("role_session_name = " + SESSION), "and carries the build's session name");
    }

    /**
     * AWS nested configuration uses indentation for sub-keys, and configparser reads an indented line as
     * a continuation of the previous option rather than as a new key. Matching those lines saw
     * {@code endpoint_url} declared twice and rejected a file botocore parses happily — costing that node
     * all attribution.
     */
    @Test
    void nestedConfigurationIsNotMistakenForDuplicateKeys() {
        String nested = "[default]\n" + "region = us-east-1\n"
                + "[services local]\n"
                + "dynamodb =\n"
                + "  endpoint_url = http://localhost:8000\n"
                + "s3 =\n"
                + "  endpoint_url = http://localhost:9000\n";

        AwsConfigOverlay.Result result = describe(nested);
        assertTrue(
                AwsConfigOverlay.validate(nested, result.content()).isEmpty(),
                "indented sub-keys are continuations, not duplicate declarations");
    }

    /** A genuine duplicate at the top level must still be caught — the guard that fix must not weaken. */
    @Test
    void genuineDuplicateKeysAreStillRejected() {
        String generated = "[profile x]\n" + "region = us-east-1\n" + "region = us-east-2\n";
        assertTrue(
                AwsConfigOverlay.validate("[profile x]\n", generated).isPresent(),
                "a key declared twice at the top level of a section is still a defect");
    }
}
