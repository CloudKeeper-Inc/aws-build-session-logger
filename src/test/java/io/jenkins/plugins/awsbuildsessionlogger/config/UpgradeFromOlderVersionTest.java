package io.jenkins.plugins.awsbuildsessionlogger.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Upgrading from a version whose configuration predates the current fields.
 *
 * <p>The whole safety case for an install rests on two claims about this moment: that an upgrade cannot
 * switch auditing on by itself, and that {@code observeOnly} lands <b>on</b>. Both were true only because
 * of how XStream treats an absent element — it unmarshals into the already-constructed object and writes
 * only the elements it finds, leaving field initialisers intact. That is Jenkins core's behaviour, not
 * this plugin's, which is exactly why it deserves a test here: nothing in this repository would notice if
 * it changed.
 */
@WithJenkins
class UpgradeFromOlderVersionTest {

    /** A file written before these settings existed: no {@code observeOnly}, no {@code attributeUnprofiledAsNodeRole}. */
    private static final String OLD_XML = "<?xml version='1.1' encoding='UTF-8'?>\n"
            + "<io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration plugin=\"aws-build-session-logger@1.0.0\">\n"
            + "  <profiles/>\n"
            + "  <managedAuthentication>false</managedAuthentication>\n"
            + "  <jobNamePattern>uat/.*</jobNamePattern>\n"
            + "  <credentialSource>Ec2InstanceMetadata</credentialSource>\n"
            + "</io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration>\n";

    @Test
    void observeOnlyLandsOnAndAuditingStaysOff(JenkinsRule r) throws Exception {
        Files.writeString(
                r.jenkins
                        .getRootDir()
                        .toPath()
                        .resolve("io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration.xml"),
                OLD_XML,
                StandardCharsets.UTF_8);

        AwsBuildSessionConfiguration upgraded = new AwsBuildSessionConfiguration();

        assertFalse(upgraded.isManagedAuthentication(), "an upgrade must never switch auditing on by itself");
        assertTrue(
                upgraded.isObserveOnly(),
                "the element is absent from older configuration, so the field initialiser must survive and "
                        + "leave the plugin in the reporting-only mode");
        assertEquals("uat/.*", upgraded.getJobNamePattern(), "settings the operator did choose must survive");
        assertFalse(upgraded.isAttributeUnprofiledAsNodeRole(), "a field absent with no initialiser stays off");
    }

    /** A configuration naming a field this version no longer has must still load. */
    @Test
    void anUnknownElementDoesNotPreventLoading(JenkinsRule r) throws Exception {
        String withRemovedField = OLD_XML.replace(
                "  <profiles/>\n", "  <profiles/>\n  <terraformOverridePattern>something</terraformOverridePattern>\n");
        Files.writeString(
                r.jenkins
                        .getRootDir()
                        .toPath()
                        .resolve("io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration.xml"),
                withRemovedField,
                StandardCharsets.UTF_8);

        AwsBuildSessionConfiguration loaded = new AwsBuildSessionConfiguration();

        assertEquals("uat/.*", loaded.getJobNamePattern(), "an unknown element must not stop the rest binding");
        assertFalse(loaded.isManagedAuthentication());
        assertTrue(loaded.isObserveOnly());
    }
}
