package io.jenkins.plugins.awsbuildsessionlogger.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import java.io.File;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Where the generated configuration is anchored.
 *
 * <p>The file must land at the build's workspace root, not wherever a {@code dir} block happens to have
 * moved to. Writing it under the current directory scatters {@code aws-build-session-logger/} through checked-out source,
 * prepares the same file once per {@code dir} block, and can leave directories no cleanup reclaims.
 *
 * <p><b>Concurrent builds are the case this exists for.</b> {@code getWorkspaceFor} always answers
 * {@code …/job}, but a second simultaneous build runs in {@code …/job@2} — which is not "inside"
 * {@code …/job}, so every concurrent build silently fell back to the current directory and re-acquired
 * all three problems. This is the only change on the managed path that touches all 802 production jobs,
 * and it had no test at all.
 */
@WithJenkins
class BuildWorkspaceAnchoringTest {

    /** Runs the real method against a path, using a real project so {@code getWorkspaceFor} is genuine. */
    private static String anchor(JenkinsRule r, String currentPath) throws Exception {
        FreeStyleProject job = r.jenkins.getItemByFullName("anchored", FreeStyleProject.class);
        if (job == null) {
            job = r.createFreeStyleProject("anchored");
        }
        FreeStyleBuild build = r.buildAndAssertSuccess(job);
        Node node = r.jenkins;
        FilePath current = new FilePath(new File(currentPath));
        FilePath result = ManagedAwsContext.buildWorkspace(current, node, build);
        return result == null ? null : result.getRemote();
    }

    /** The canonical workspace root for this job on the built-in node. */
    private static String root(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.jenkins.getItemByFullName("anchored", FreeStyleProject.class);
        if (job == null) {
            job = r.createFreeStyleProject("anchored");
        }
        FilePath ws = r.jenkins.getWorkspaceFor(job);
        return ws == null ? null : ws.getRemote();
    }

    @Test
    void aPathInsideTheWorkspaceAnchorsToTheWorkspaceRoot(JenkinsRule r) throws Exception {
        String ws = root(r);
        assertEquals(ws, anchor(r, ws + "/app/service"), "a dir() block must still prepare at the root");
        assertEquals(ws, anchor(r, ws), "the root itself anchors to itself");
    }

    @Test
    void aConcurrentBuildAnchorsToItsOwnAtSuffixedRoot(JenkinsRule r) throws Exception {
        String ws = root(r);
        assertEquals(ws + "@2", anchor(r, ws + "@2"), "job@2 is a workspace root in its own right");
        assertEquals(ws + "@2", anchor(r, ws + "@2/app/service"), "and a dir() inside it anchors to it");
        assertEquals(ws + "@13", anchor(r, ws + "@13/deep/er"), "multi-digit build numbers too");
    }

    /**
     * {@code @tmp}, {@code @script} and {@code @libs} are Jenkins' own sibling directories, not
     * concurrent-build roots. Anchoring to them would put the generated file somewhere cleanup does not
     * expect, so they must fall back to the path given.
     */
    @Test
    void jenkinsOwnAtSuffixesAreNotTreatedAsConcurrentRoots(JenkinsRule r) throws Exception {
        String ws = root(r);
        assertEquals(ws + "@tmp", anchor(r, ws + "@tmp"), "@tmp is not a concurrent root");
        assertEquals(ws + "@script", anchor(r, ws + "@script"), "@script is not a concurrent root");
        assertEquals(ws + "@2x", anchor(r, ws + "@2x"), "@2x is not a build number");
        assertEquals(ws + "@", anchor(r, ws + "@"), "a bare @ is not a build number");
    }

    @Test
    void aPathOutsideTheWorkspaceIsLeftAlone(JenkinsRule r) throws Exception {
        assertEquals(
                "/tmp/somewhere-else",
                anchor(r, "/tmp/somewhere-else"),
                "ws('other') and friends keep the path they were given");
    }
}
