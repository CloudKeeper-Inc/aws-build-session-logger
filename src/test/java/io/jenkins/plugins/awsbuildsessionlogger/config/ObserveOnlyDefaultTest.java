package io.jenkins.plugins.awsbuildsessionlogger.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The shipped default pair: master switch off, observe-only on.
 *
 * <p>These two defaults are the whole safety story of an install. A fresh controller does nothing at
 * all, because the master switch gates everything; and the first time an administrator turns that
 * switch on, the safe mode is already selected, so the action reports what would happen instead of
 * changing the environment of every in-scope build at once.
 *
 * <p>Pinned in a test because a default is exactly the kind of thing that gets flipped by accident —
 * and because the pair only makes sense together. Turning {@code observeOnly} off by default would
 * silently convert the first click of the master switch into an estate-wide change.
 */
@WithJenkins
class ObserveOnlyDefaultTest {

    @Test
    void managedAuthenticationIsOffAndObserveOnlyIsOnByDefault(JenkinsRule r) {
        AwsBuildSessionConfiguration config = new AwsBuildSessionConfiguration();

        assertFalse(
                config.isManagedAuthentication(),
                "the master switch must ship off: installing must change no build's behaviour");
        assertTrue(
                config.isObserveOnly(),
                "observe-only must ship on, so enabling the master switch is reportable rather than "
                        + "immediately enforcing");
    }
}
