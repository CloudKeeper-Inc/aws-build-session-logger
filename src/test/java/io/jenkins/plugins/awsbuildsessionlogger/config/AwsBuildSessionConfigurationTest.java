package io.jenkins.plugins.awsbuildsessionlogger.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.FormValidation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Tests for the Jenkins-owned profile mapping.
 *
 * <p>{@link JenkinsRule} is genuinely required: what is under test is extension registration, form
 * data binding and configuration persistence.
 *
 * <p>Configuration as Code is covered end to end, with real YAML, in {@link ConfigurationAsCodeTest}.
 * The tests here exercise the same surface JCasC uses: the {@code @Symbol}, structs'
 * {@code DescribableModel}, and the {@code @DataBoundSetter}s.
 */
@WithJenkins
class AwsBuildSessionConfigurationTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/non_prod";

    @Test
    void isRegisteredAndStartsEmpty(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();

        assertNotNull(configuration, "global configuration should be registered as an extension");
        assertTrue(configuration.getProfiles().isEmpty(), "no profiles should be configured by default");
        assertTrue(configuration.configuredProfileNames().isEmpty());
    }

    @Test
    void resolvesAConfiguredProfile(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = configureWith(profile("non_prod", ROLE, "us-east-1"));

        Optional<AwsProfile> resolved = configuration.resolve("non_prod");

        assertTrue(resolved.isPresent());
        assertEquals(ROLE, resolved.get().getRoleArn());
        assertEquals("us-east-1", resolved.get().getRegion());
    }

    @Test
    void resolutionIsExactAndCaseSensitive(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = configureWith(profile("non_prod", ROLE, null));

        // Fail closed: a near-miss must not resolve to something. Assuming the wrong identity because a
        // name was nearly right is the failure mode this whole layer exists to prevent.
        assertTrue(configuration.resolve("NON_PROD").isEmpty(), "resolution should be case-sensitive");
        assertTrue(configuration.resolve("non_pro").isEmpty());
        assertTrue(configuration.resolve("prod").isEmpty());
        assertTrue(configuration.resolve(null).isEmpty());
        assertTrue(configuration.resolve("   ").isEmpty());
    }

    @Test
    void surroundingWhitespaceInALookupIsTolerated(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = configureWith(profile("non_prod", ROLE, null));

        assertTrue(configuration.resolve("  non_prod  ").isPresent());
    }

    @Test
    void aNamelessProfileIsTreatedAsAbsent(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = configureWith(profile("", ROLE, null));

        assertTrue(configuration.configuredProfileNames().isEmpty(), "an entry with no name is unusable");
    }

    @Test
    void anInstanceProfileModeEntryIsUsableWithoutARoleArn(JenkinsRule j) {
        // An instance-profile entry declares the same-account case, where there is nothing to assume.
        // It is a valid entry, but managed authentication does not render it (the agent's own config
        // must define the profile), and the block step refuses it explicitly.
        AwsProfile ops = new AwsProfile("ops", null);
        ops.setMode(AwsProfile.INSTANCE_PROFILE);
        AwsBuildSessionConfiguration configuration = configureWith(ops);

        assertTrue(configuration.resolve("ops").isPresent());
        assertFalse(configuration.resolve("ops").get().hasRole());
        assertEquals(List.of("ops"), configuration.configuredProfileNames());
    }

    @Test
    void anAssumeRoleEntryWithoutAnArnIsExcludedRatherThanDowngraded(JenkinsRule j) {
        // The mode is declared, not inferred. An administrator who clears the ARN of an assume-role
        // profile has broken it — and the build must be told, not quietly moved onto the agent's
        // identity, which is the exact failure this plugin exists to prevent.
        AwsBuildSessionConfiguration configuration = configureWith(profile("non_prod", "", null));

        assertTrue(configuration.configuredProfileNames().isEmpty());
        assertEquals(
                FormValidation.Kind.ERROR,
                j.jenkins
                        .getDescriptorByType(AwsProfile.DescriptorImpl.class)
                        .doCheckRoleArn("", AwsProfile.ASSUME_ROLE)
                        .kind);
    }

    @Test
    void theModeSurvivesAFormRoundTripAndDefaultsToAssumeRole(JenkinsRule j) throws Exception {
        AwsProfile ops = new AwsProfile("ops", null);
        ops.setMode(AwsProfile.INSTANCE_PROFILE);
        configureWith(profile("non_prod", ROLE, null), ops);

        j.configRoundtrip();

        List<AwsProfile> reloaded = AwsBuildSessionConfiguration.get().getProfiles();
        assertEquals(2, reloaded.size());
        assertEquals(AwsProfile.ASSUME_ROLE, reloaded.get(0).getMode());
        assertEquals(AwsProfile.INSTANCE_PROFILE, reloaded.get(1).getMode());
    }

    @Test
    void configurationSavedBeforeTheModeExistedIsTreatedAsAssumeRole(JenkinsRule j) throws Exception {
        // The upgrade path: profiles persisted by builds that predate the mode carry no mode at all, and every
        // one of them was an assume-role profile. Absence must therefore mean assume-role.
        Map<String, Object> yamlWithoutMode = new LinkedHashMap<>();
        yamlWithoutMode.put("name", "non_prod");
        yamlWithoutMode.put("roleArn", ROLE);

        AwsProfile bound = DescribableModel.of(AwsProfile.class).instantiate(yamlWithoutMode);

        assertEquals(AwsProfile.ASSUME_ROLE, bound.getMode());
        assertTrue(bound.hasRole());
        assertTrue(bound.isUsable());
    }

    @Test
    void profileNamesThatCouldInjectIntoTheGeneratedConfigAreRejected(JenkinsRule j) {
        // A profile name becomes an INI section header, so a bracket or newline could introduce
        // arbitrary keys; whitespace is rejected because the AWS config parser cannot read it at all.
        AwsBuildSessionConfiguration configuration =
                configureWith(profile("with space", ROLE, null), profile("bad]\n[profile prod", ROLE, null));

        assertTrue(configuration.configuredProfileNames().isEmpty());
        assertEquals(
                FormValidation.Kind.ERROR,
                j.jenkins.getDescriptorByType(AwsProfile.DescriptorImpl.class).doCheckName("with space").kind);
    }

    // --- the Managed Authentication switches ----------------------------------

    @Test
    void managedAuthenticationIsOffByDefault(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        assertNotNull(configuration);

        // Installing or upgrading the plugin must change no build's behaviour until an administrator
        // opts in. This is also what makes rollback a checkbox rather than a Jenkins restart.
        assertFalse(configuration.isManagedAuthentication());
        assertFalse(configuration.appliesTo("any/job"));
        assertEquals(AwsBuildSessionConfiguration.DEFAULT_CREDENTIAL_SOURCE, configuration.getCredentialSource());
    }

    @Test
    void theJobPatternSelectsWhichJobsAreManaged(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = configureWith(profile("non_prod", ROLE, null));
        configuration.setManagedAuthentication(true);

        assertTrue(configuration.appliesTo("anything"), "a blank pattern means every job");

        configuration.setJobNamePattern("uat/.*");
        assertTrue(configuration.appliesTo("uat/Backend/deploy"));
        assertFalse(configuration.appliesTo("prod/Backend/deploy"));

        // A typo must narrow a rollout, never silently widen it to the whole controller.
        configuration.setJobNamePattern("uat/[");
        assertFalse(configuration.appliesTo("uat/Backend/deploy"));
    }

    @Test
    void theCredentialSourceIsConstrainedToWhatAwsAccepts(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        assertNotNull(configuration);

        configuration.setCredentialSource("EcsContainer");
        assertEquals("EcsContainer", configuration.getCredentialSource());

        // Anything botocore would reject falls back to the default rather than producing a file the
        // AWS SDKs refuse to parse.
        configuration.setCredentialSource("NotAThing");
        assertEquals(AwsBuildSessionConfiguration.DEFAULT_CREDENTIAL_SOURCE, configuration.getCredentialSource());
    }

    @Test
    void listsConfiguredNamesForErrorMessages(JenkinsRule j) {
        AwsBuildSessionConfiguration configuration =
                configureWith(profile("non_prod", ROLE, null), profile("prod", ROLE, null));

        assertEquals(List.of("non_prod", "prod"), configuration.configuredProfileNames());
    }

    @Test
    void configurationSurvivesAFormRoundTrip(JenkinsRule j) throws Exception {
        configureWith(profile("non_prod", ROLE, "us-east-1"));

        // Exercises the jelly and the data binding the UI actually uses.
        j.configRoundtrip();

        AwsBuildSessionConfiguration reloaded = AwsBuildSessionConfiguration.get();
        assertNotNull(reloaded);
        assertEquals(1, reloaded.getProfiles().size());
        AwsProfile profile = reloaded.getProfiles().get(0);
        assertEquals("non_prod", profile.getName());
        assertEquals(ROLE, profile.getRoleArn());
        assertEquals("us-east-1", profile.getRegion());
    }

    @Test
    void anEmptyRegionIsStoredAsAbsentRatherThanBlank(JenkinsRule j) {
        // A blank region must not be exported as AWS_REGION="" - that is worse than not setting it,
        // because it overrides whatever the agent would otherwise have resolved.
        AwsProfile profile = new AwsProfile("non_prod", ROLE);
        profile.setRegion("   ");

        assertNull(profile.getRegion());
    }

    @Test
    void profileNamesAndArnsAreTrimmed(JenkinsRule j) {
        AwsProfile profile = new AwsProfile("  non_prod  ", "  " + ROLE + "  ");

        assertEquals("non_prod", profile.getName());
        assertEquals(ROLE, profile.getRoleArn());
        assertTrue(profile.isComplete());
    }

    // --- the JCasC-facing contract -------------------------------------------

    @Test
    void exposesTheSymbolsJcascAddressesItBy(JenkinsRule j) {
        // unclassified.awsBuildSessionLogger.profiles in YAML resolves through these two symbols. Renaming either is a
        // breaking change to every JCasC file in the field, so it is pinned by a test.
        Symbol configurationSymbol = AwsBuildSessionConfiguration.class.getAnnotation(Symbol.class);
        assertNotNull(configurationSymbol, "the global configuration must carry a @Symbol for JCasC");
        assertEquals("awsBuildSessionLogger", configurationSymbol.value()[0]);

        Symbol profileSymbol = AwsProfile.DescriptorImpl.class.getAnnotation(Symbol.class);
        assertNotNull(profileSymbol, "the profile descriptor must carry a @Symbol");
        assertEquals("awsProfile", profileSymbol.value()[0]);
    }

    @Test
    void bindsFromTheNestedMapsJcascProducesFromYaml(JenkinsRule j) throws Exception {
        // This is the shape JCasC hands to structs for:
        //   unclassified:
        //     awsBuildSessionLogger:
        //       profiles:
        //         - name: "non_prod"
        //           roleArn: "arn:aws:iam::123456789012:role/non_prod"
        //           region: "us-east-1"
        Map<String, Object> yamlShapedProfile = new LinkedHashMap<>();
        yamlShapedProfile.put("name", "non_prod");
        yamlShapedProfile.put("roleArn", ROLE);
        yamlShapedProfile.put("region", "us-east-1");

        AwsProfile bound = DescribableModel.of(AwsProfile.class).instantiate(yamlShapedProfile);

        assertEquals("non_prod", bound.getName());
        assertEquals(ROLE, bound.getRoleArn());
        assertEquals("us-east-1", bound.getRegion());

        // A GlobalConfiguration is a singleton, so JCasC does not instantiate one: it looks up the
        // existing instance by symbol and pushes each attribute onto it through the @DataBoundSetter.
        // That setter is therefore the actual JCasC entry point, and it is what the rest of this test
        // exercises - with a value built by structs from the YAML shape above, so the whole chain from
        // 'what the YAML says' to 'what resolve() returns' is covered.
        Method setter = AwsBuildSessionConfiguration.class.getMethod("setProfiles", List.class);
        assertNotNull(
                setter.getAnnotation(DataBoundSetter.class),
                "setProfiles must be a @DataBoundSetter - it is how JCasC writes unclassified.awsBuildSessionLogger.profiles");

        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        assertNotNull(configuration);
        setter.invoke(configuration, List.of(bound));

        assertTrue(
                AwsBuildSessionConfiguration.get().resolve("non_prod").isPresent(),
                "configuring the way JCasC does should populate the mapping");
        assertEquals(
                ROLE,
                AwsBuildSessionConfiguration.get().resolve("non_prod").get().getRoleArn());
    }

    @Test
    void regionIsOptionalInTheBoundForm(JenkinsRule j) throws Exception {
        Map<String, Object> withoutRegion = new LinkedHashMap<>();
        withoutRegion.put("name", "sandbox");
        withoutRegion.put("roleArn", ROLE);

        AwsProfile bound = DescribableModel.of(AwsProfile.class).instantiate(withoutRegion);

        assertEquals("sandbox", bound.getName());
        assertNull(bound.getRegion(), "an omitted region must stay absent, not become empty string");
    }

    // --- form validation ------------------------------------------------------

    @Test
    void formValidation(JenkinsRule j) {
        AwsProfile.DescriptorImpl descriptor = j.jenkins.getDescriptorByType(AwsProfile.DescriptorImpl.class);
        assertNotNull(descriptor);

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckName("").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckName("non_prod").kind);

        // Whether an ARN is required depends on the declared mode.
        assertEquals(
                FormValidation.Kind.ERROR,
                descriptor.doCheckRoleArn("  ", AwsProfile.ASSUME_ROLE).kind,
                "assume-role mode has nothing to assume without an ARN");
        assertEquals(
                FormValidation.Kind.OK,
                descriptor.doCheckRoleArn("  ", AwsProfile.INSTANCE_PROFILE).kind,
                "instance-profile mode does not need one");
        assertEquals(
                FormValidation.Kind.WARNING,
                descriptor.doCheckRoleArn(ROLE, AwsProfile.INSTANCE_PROFILE).kind,
                "an ARN left behind after switching mode is ignored, and the admin should be told");
        assertEquals(
                FormValidation.Kind.ERROR,
                descriptor.doCheckRoleArn(ROLE + "\ncredential_process = x", AwsProfile.ASSUME_ROLE).kind);
        // Advisory, not an error: unanticipated ARN shapes must not be rejected by this plugin.
        assertEquals(FormValidation.Kind.WARNING, descriptor.doCheckRoleArn("non_prod", AwsProfile.ASSUME_ROLE).kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckRoleArn(ROLE, AwsProfile.ASSUME_ROLE).kind);
    }

    @Test
    void profileToStringCarriesNothingSensitive(JenkinsRule j) {
        // A role ARN is not a secret and there is nothing else here - but assert it, so that anyone
        // later adding a field has to think about this line.
        String rendered = profile("non_prod", ROLE, "us-east-1").toString();

        assertTrue(rendered.contains("non_prod"));
        assertFalse(rendered.toLowerCase().contains("secret"));
        assertFalse(rendered.toLowerCase().contains("token"));
    }

    // --- helpers --------------------------------------------------------------

    private static AwsBuildSessionConfiguration configureWith(AwsProfile... profiles) {
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        assertNotNull(configuration);
        configuration.setProfiles(List.of(profiles));
        return configuration;
    }

    private static AwsProfile profile(String name, String roleArn, String region) {
        AwsProfile profile = new AwsProfile(name, roleArn);
        profile.setMode(AwsProfile.ASSUME_ROLE);
        profile.setRegion(region);
        return profile;
    }

    @Test
    void malformedRoleArnsAreReportedByName(JenkinsRule j) {
        AwsProfile good = new AwsProfile("good", "arn:aws:iam::123456789012:role/deploy");
        AwsProfile typo = new AwsProfile("typo", "non_prod");
        AwsProfile userArn = new AwsProfile("user", "arn:aws:iam::123456789012:user/someone");
        AwsProfile instance = new AwsProfile("same-account", null);
        instance.setMode(AwsProfile.INSTANCE_PROFILE);

        assertEquals(
                List.of("typo", "user"),
                AwsBuildSessionConfiguration.malformedRoleArns(List.of(good, typo, userArn, instance)),
                "only assume-role profiles with a non-role ARN are reported");
        assertTrue(AwsBuildSessionConfiguration.malformedRoleArns(null).isEmpty());
    }
}
