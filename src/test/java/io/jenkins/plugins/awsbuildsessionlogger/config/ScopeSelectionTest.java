package io.jenkins.plugins.awsbuildsessionlogger.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Which builds Managed Authentication applies to.
 *
 * <p>Every assertion here is about <b>failing towards a narrower scope</b>. Losing attribution on a
 * build is recoverable; switching authentication on across the whole controller because a regular
 * expression had a typo is not. Each fallback below is chosen deliberately, and pinned here so it
 * cannot be quietly reversed.
 *
 * <p>{@link JenkinsRule} is required only because constructing the configuration loads and saves it;
 * the selection logic itself is a pure function of the fields.
 */
@WithJenkins
class ScopeSelectionTest {

    private static AwsBuildSessionConfiguration enabled() {
        AwsBuildSessionConfiguration configuration = new AwsBuildSessionConfiguration();
        configuration.setManagedAuthentication(true);
        return configuration;
    }

    // --- the safety default --------------------------------------------------

    @Test
    void managedAuthenticationIsOffUntilSomebodyTurnsItOn(JenkinsRule j) {
        AwsBuildSessionConfiguration fresh = new AwsBuildSessionConfiguration();

        assertFalse(fresh.isManagedAuthentication(), "installing the plugin must change no build's behaviour");
        assertFalse(fresh.appliesTo("any/job"), "and nothing may be in scope while it is off");
    }

    @Test
    void diagnosticsAreOffUntilSomebodyTurnsThemOn(JenkinsRule j) {
        assertFalse(new AwsBuildSessionConfiguration().isDiagnostics());
    }

    @Test
    void nothingAppliesWhileTheMasterSwitchIsOffHoweverElseItIsConfigured(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = new AwsBuildSessionConfiguration();
        configuration.setJobNamePattern(".*");
        configuration.setUnprofiledRoleArn("arn:aws:iam::111111111111:role/x");

        assertFalse(configuration.appliesTo("uat/anything"));
    }

    // --- include -------------------------------------------------------------

    @Test
    void aBlankIncludePatternAppliesToEveryJob(JenkinsRule j) {
        assertTrue(enabled().appliesTo("uat/Backend/deploy"));
    }

    @Test
    void theIncludePatternMatchesTheWholeJobName(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setJobNamePattern("^dev/service-a$");

        assertTrue(configuration.appliesTo("dev/service-a"));
        assertFalse(configuration.appliesTo("dev/service-a-staging"), "a full match must not match a prefix");
        assertFalse(configuration.appliesTo("other/dev/service-a"), "nor a suffix");
        assertFalse(configuration.appliesTo("service-a"), "and the folder is part of the name");
    }

    @Test
    void anchorsAreOptionalBecauseMatchingIsAlreadyFullString(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setJobNamePattern("dev/service-a");

        assertTrue(configuration.appliesTo("dev/service-a"));
        assertFalse(configuration.appliesTo("dev/service-a-staging"));
    }

    /** The direction that matters: a typo must not switch the whole controller on. */
    @Test
    void anUnparseableIncludePatternMatchesNothing(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setJobNamePattern("dev2/[unclosed");

        assertFalse(configuration.appliesTo("dev/service-a"));
        assertFalse(configuration.appliesTo("anything"));
    }

    // --- exclude -------------------------------------------------------------

    @Test
    void aBlankExcludePatternExcludesNothing(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setJobNamePattern(".*");

        assertTrue(configuration.appliesTo("uat/batchprocessor"));
    }

    @Test
    void excludeIsEvaluatedAfterIncludeAndWins(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setJobNamePattern("uat/.*");
        configuration.setJobNameExcludePattern("uat/batchprocessor");

        assertTrue(configuration.appliesTo("uat/frontend"), "the rest of the folder stays in scope");
        assertFalse(configuration.appliesTo("uat/batchprocessor"), "the excluded job does not");
    }

    @Test
    void excludeCannotWidenScopeBeyondInclude(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setJobNamePattern("uat/.*");
        configuration.setJobNameExcludePattern("prod/.*");

        assertFalse(configuration.appliesTo("prod/frontend"), "excluding something never in scope changes nothing");
    }

    /**
     * Deliberately the opposite fallback to include. An unparseable exclude that excluded everything
     * would silently switch attribution off across the controller — the very failure this field exists
     * to prevent.
     */
    @Test
    void anUnparseableExcludePatternExcludesNothing(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setJobNamePattern(".*");
        configuration.setJobNameExcludePattern("[unclosed");

        assertTrue(configuration.appliesTo("uat/frontend"));
    }

    // --- node scoping --------------------------------------------------------

    @Test
    void aBlankNodePatternAppliesOnEveryNode(JenkinsRule j) {
        assertTrue(enabled().appliesToNode(Set.of("anything")));
        assertTrue(enabled().appliesToNode(Set.of()), "including a node reporting no labels at all");
        assertTrue(enabled().appliesToNode(null));
    }

    @Test
    void aNodePatternMatchesAnyOneOfTheNodesLabels(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setNodeLabelPattern("linux");

        assertTrue(configuration.appliesToNode(Set.of("docker", "linux", "ec2")));
        assertFalse(configuration.appliesToNode(Set.of("docker", "windows")));
    }

    @Test
    void nodeLabelsAreMatchedInFullNotAsSubstrings(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setNodeLabelPattern("agent-a");

        assertTrue(configuration.appliesToNode(Set.of("agent-a")));
        assertFalse(configuration.appliesToNode(Set.of("agent-abc")));
    }

    @Test
    void aNodePatternWithNoLabelsToMatchAgainstNarrowsRatherThanWidens(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setNodeLabelPattern("linux");

        assertFalse(configuration.appliesToNode(Set.of()));
        assertFalse(configuration.appliesToNode(null));
    }

    @Test
    void anUnparseableNodePatternMatchesNothing(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setNodeLabelPattern("[unclosed");

        assertFalse(configuration.appliesToNode(Set.of("linux")));
    }

    // --- the unprofiled identity --------------------------------------------

    @Test
    void theUnprofiledRoleIsAbsentUntilConfiguredAndBlankMeansAbsent(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        assertNull(configuration.getUnprofiledRoleArn());

        configuration.setUnprofiledRoleArn("   ");
        assertNull(configuration.getUnprofiledRoleArn(), "whitespace must not become a role ARN");
    }

    @Test
    void roleArnShapeIsCheckedButPartitionAndPathAreNot(JenkinsRule j) {
        assertTrue(AwsProfile.looksLikeRoleArn("arn:aws:iam::111111111111:role/plain"));
        assertTrue(AwsProfile.looksLikeRoleArn("arn:aws-cn:iam::111111111111:role/china"));
        assertTrue(AwsProfile.looksLikeRoleArn("arn:aws-us-gov:iam::111111111111:role/gov"));
        assertTrue(AwsProfile.looksLikeRoleArn("arn:aws:iam::111111111111:role/with/a/path"));

        assertFalse(AwsProfile.looksLikeRoleArn("just-a-role-name"));
        assertFalse(AwsProfile.looksLikeRoleArn("arn:aws:iam::111111111111:user/not-a-role"));
        assertFalse(AwsProfile.looksLikeRoleArn("arn:aws:iam::abc:role/bad-account"));
        assertFalse(AwsProfile.looksLikeRoleArn(""));
        assertFalse(AwsProfile.looksLikeRoleArn(null));
    }

    // --- the pattern semantics an administrator relies on --------------------

    @Test
    void aDotIsAWildcardWhichIsWorthKnowingBeforeWritingAPattern(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setJobNamePattern("dev.service-a");

        assertTrue(configuration.appliesTo("dev/service-a"), "an unescaped dot matches the separator too");
        assertTrue(configuration.appliesTo("dev-service-a"));

        configuration.setJobNamePattern("dev\\.service-a");
        assertFalse(configuration.appliesTo("dev/service-a"), "escape it when a literal dot is meant");
    }

    @Test
    void alternationSelectsSeveralNamedJobs(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = enabled();
        configuration.setJobNamePattern("^dev/(service-a|service-b)$");

        for (String job : List.of("dev/service-a", "dev/service-b")) {
            assertTrue(configuration.appliesTo(job), job + " should be in scope");
        }
        assertFalse(configuration.appliesTo("dev/frontend"));
    }
}
