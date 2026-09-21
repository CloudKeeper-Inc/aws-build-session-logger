package io.jenkins.plugins.awsbuildsessionlogger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.PluginWrapper;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Smoke test: asserts the plugin is registered and active in a real Jenkins.
 *
 * <p>This is intentionally the only test that needs a running Jenkins. Auth and executor
 * logic added in later milestones must stay testable without {@link JenkinsRule}.
 */
@WithJenkins
class PluginLoadsTest {

    @Test
    void pluginIsInstalledAndActive(JenkinsRule j) {
        PluginWrapper plugin = j.jenkins.getPluginManager().getPlugin("aws-build-session-logger");
        assertNotNull(plugin, "aws-build-session-logger plugin should be installed");
        assertTrue(plugin.isActive(), "aws-build-session-logger plugin should be active");
    }
}
