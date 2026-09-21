package io.jenkins.plugins.awsbuildsessionlogger.config;

import static io.jenkins.plugins.casc.misc.Util.getUnclassifiedRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.casc.model.CNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end Configuration as Code: real YAML in, real YAML out.
 *
 * <p>Profiles are not on the settings page, so JCasC (or raw XML) is the only way an administrator can
 * set them. This is the test that proves that path works.
 */
@WithJenkinsConfiguredWithCode
class ConfigurationAsCodeTest {

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void loadsEverySetting(JenkinsConfiguredWithCodeRule j) {
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        assertNotNull(configuration);
        assertTrue(configuration.isManagedAuthentication());
        assertFalse(configuration.isObserveOnly());
        assertEquals("team-a/.*", configuration.getJobNamePattern());
        assertEquals("team-a/experimental", configuration.getJobNameExcludePattern());
        assertTrue(configuration.isAttributeUnprofiledAsNodeRole());
        assertEquals("EcsContainer", configuration.getCredentialSource());

        List<AwsProfile> profiles = configuration.getProfiles();
        assertEquals(2, profiles.size());
        AwsProfile nonProd = configuration.resolve("non_prod").orElseThrow();
        assertEquals(AwsProfile.ASSUME_ROLE, nonProd.getMode());
        assertEquals("arn:aws:iam::123456789012:role/non_prod", nonProd.getRoleArn());
        assertEquals("us-east-1", nonProd.getRegion());
        assertEquals(
                AwsProfile.INSTANCE_PROFILE,
                configuration.resolve("ops").orElseThrow().getMode());

        assertTrue(configuration.appliesTo("team-a/deploy"));
        assertFalse(configuration.appliesTo("team-a/experimental"), "the exclude pattern wins");
        assertFalse(configuration.appliesTo("team-b/deploy"));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void exportsWhatWasLoaded(JenkinsConfiguredWithCodeRule j) throws Exception {
        ConfigurationContext context = new ConfigurationContext(ConfiguratorRegistry.get());
        CNode node = getUnclassifiedRoot(context).get("awsBuildSessionLogger");
        assertNotNull(node, "the configuration is exported under its @Symbol");

        String exported = toYamlString(node);
        for (String expected : List.of(
                "managedAuthentication: true",
                "observeOnly: false",
                "team-a/experimental",
                "EcsContainer",
                "non_prod",
                "arn:aws:iam::123456789012:role/non_prod",
                "InstanceProfile")) {
            assertTrue(exported.contains(expected), "export should contain " + expected + ":\n" + exported);
        }
    }
}
