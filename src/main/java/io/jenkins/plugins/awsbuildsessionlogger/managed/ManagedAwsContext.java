package io.jenkins.plugins.awsbuildsessionlogger.managed;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.WorkspaceList;
import io.jenkins.plugins.awsbuildsessionlogger.auth.SessionName;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsBuildSessionConfiguration;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsProfile;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.SystemProperties;
import org.jenkinsci.plugins.workflow.steps.DynamicContext;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;

/**
 * Managed Authentication: the plugin decorates the authentication a build was already going to use.
 *
 * <p>Jenkins consults a {@link DynamicContext} whenever a step needs a context value it has not been
 * given, and step environment is computed through {@link EnvironmentExpander}. Contributing one here
 * therefore reaches <em>every</em> step of every Pipeline — shared libraries, {@code parallel}
 * branches, {@code retry} attempts, second {@code node} blocks on other agents — with nothing to add to
 * a Jenkinsfile and nothing for anyone to remember.
 *
 * <p><b>What it does.</b> It reads the executing node's own AWS configuration, copies it into the
 * build's private temporary directory with {@code role_session_name = jk-<job>-<build>} added to
 * profiles that assume a role, and points {@code AWS_CONFIG_FILE} at the copy. Role ARNs, base
 * identities, chained profiles and regions are the node's; the plugin contributes a label and nothing
 * else.
 *
 * <p><b>What it deliberately does not do.</b> It does not set
 * {@code AWS_SHARED_CREDENTIALS_FILE} — measured: pointing that at a plugin-owned file breaks any
 * profile that chains through {@code source_profile} to a credentials-file profile, with
 * <em>"The source_profile … does not exist"</em>. It exports no credentials. It never authenticates on
 * the build's behalf.
 *
 * <p><b>Fail-safe is the governing property.</b> Missing configuration, an unreadable file, no
 * {@code HOME}, a workspace that cannot be written, an unexpected exception — every one of them
 * results in contributing nothing, and the build authenticates exactly as it does today. Losing
 * attribution is acceptable; failing a deployment is not.
 *
 * <p><b>The hot path is cheap.</b> Jenkins recomputes dynamic context on every query and never
 * caches the result, so everything after the first step of a {@code node} block is a map lookup plus
 * one existence check on the generated file.
 */
@Extension
public final class ManagedAwsContext extends DynamicContext.Typed<EnvironmentExpander> {

    private static final Logger LOGGER = Logger.getLogger(ManagedAwsContext.class.getName());

    /** Name of the generated directory inside the build's private temporary directory. */
    static final String DIRECTORY_NAME = "aws-build-session-logger";

    static final String CONFIG_FILE = "config";

    /** Where a node keeps its AWS configuration, unless {@code AWS_CONFIG_FILE} says otherwise. */
    private static final String DEFAULT_CONFIG_RELATIVE_PATH = ".aws/config";

    /**
     * Overrides the node configuration path. A test hook, in the same spirit as the existing
     * {@code io.jenkins.plugins.awsbuildsessionlogger.awsExecutable} property: {@link Computer#getEnvironment()} reports the
     * agent process's real operating-system environment, which is exactly what production needs and
     * exactly what a test cannot fake from inside the same JVM.
     */
    private static final String CONFIG_PATH_PROPERTY = "io.jenkins.plugins.awsbuildsessionlogger.nodeConfigFile";

    /**
     * Prints what the plugin discovered to the build console.
     *
     * <p>Set {@code -Dio.jenkins.plugins.awsbuildsessionlogger.diagnostics=true} on the controller when a build
     * authenticates as something unexpected. Everything printed is non-sensitive: environment variable
     * names and values that are file paths and profile names, section headers, and the session name. No
     * file content, no credentials.
     */
    private static final String DIAGNOSTICS_PROPERTY = "io.jenkins.plugins.awsbuildsessionlogger.diagnostics";

    /**
     * Keyed by {@code run id + workspace}, so a second {@code node} block on another agent prepares its
     * own copy while every later step in the same workspace is a lookup. Evicted when the build
     * finalizes; leaving entries would leak one per node block, forever.
     */
    private static final Map<String, Map<String, String>> PREPARED = new ConcurrentHashMap<>();

    /** The variable a build's AWS SDKs read the generated configuration from. */
    static final String CONFIG_VARIABLE = "AWS_CONFIG_FILE";

    /** The build's session name, exported so a script can log or tag with it. */
    static final String SESSION_VARIABLE = "AWS_BUILD_SESSION_NAME";

    /**
     * The standard AWS variable for naming an assumed-role session, exported so that a tool which
     * assumes a role <em>itself</em> still names the session after the build.
     *
     * <p><b>Why this is needed.</b> The generated configuration names the session for every role the
     * shared config assumes, which covers the AWS CLI, boto3 and Terraform's default credential
     * resolution. It does not cover a <em>second</em> hop performed by the tool: a Terraform provider
     * carrying its own {@code assume_role} block assumes a further role from the already-assumed
     * session and, with no {@code session_name} in that block, the provider picks the name. Those
     * calls - the ones that actually change infrastructure - then carry a generated name rather than
     * {@code jk-<job>-<build>}, and CloudTrail only ties them back through the AssumeRole event.
     *
     * <p><b>This does not fix the Terraform case.</b> It was measured: the Terraform AWS provider
     * builds the second AssumeRole from its {@code assume_role} block alone and ignores this variable,
     * generating {@code aws-go-sdk-<nanotime>} when {@code session_name} is absent. Those calls stay
     * traceable through the AssumeRole event, whose caller is the build's {@code jk-} session. The
     * only direct fix is {@code session_name} in the provider block. The variable is kept because it
     * is additive and may name a second hop for a tool that does read it.
     *
     * <p>It is contributed like everything else - as an addition. A job that sets
     * {@code AWS_ROLE_SESSION_NAME} itself keeps its own value, because the merge expands the
     * plugin's values first and the enclosing environment wins.
     */
    static final String ROLE_SESSION_NAME_VARIABLE = "AWS_ROLE_SESSION_NAME";

    /**
     * One monitor per prepared key, so concurrent branches sharing a workspace prepare it once between
     * them rather than racing to write the same file. Evicted with {@link #PREPARED}.
     */
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    /**
     * Each node's own instance-role ARN, resolved once per node. Failures are cached too, as
     * {@link #UNRESOLVABLE}, because re-resolving costs two IMDS timeouts plus up to thirty seconds in
     * the self-assume probe, under the preparation lock, on every build.
     *
     * <p><b>Known limitation.</b> The key is the node name, and nothing invalidates it. That is exact
     * for ephemeral cloud agents, which get a fresh name per instance. It is <em>not</em> exact for a
     * permanent agent, or an EC2 template with a fixed node name, that is destroyed and re-provisioned
     * with a different instance role under the same name: the stale ARN would then be written into
     * {@code [default]} and the new instance may not be permitted to assume it, failing its bare
     * {@code aws} calls. No such agent exists on this controller today — every template is ephemeral —
     * but a restart clears the cache, and keying on the resolved instance ID would close it properly.
     */
    private static final Map<String, String> NODE_ROLE_ARNS = new ConcurrentHashMap<>();

    /**
     * Sentinel for "this node has no usable instance role", since {@link ConcurrentHashMap} cannot hold
     * a null value. Not a valid ARN, so it can never collide with a real one.
     */
    private static final String UNRESOLVABLE = "(node-role-unresolvable)";

    /** Upper bound on {@link #NODE_ROLE_ARNS}; far above any real fleet's concurrent node count. */
    private static final int MAX_CACHED_NODE_ROLES = 500;

    @Override
    protected Class<EnvironmentExpander> type() {
        return EnvironmentExpander.class;
    }

    /**
     * The whole contribution path, guarded.
     *
     * <p>Everything is inside {@link #guarded}: the configuration lookup, every
     * {@link DelegatedContext#get} — each of which is declared to throw {@link IOException} — and the
     * decoration itself. An earlier version guarded only the decoration, which left four statements and
     * the whole of {@link Error} outside the net; anything escaping here propagates through
     * {@code ContextVariableSet} into the step and fails the build, which is the one outcome this
     * feature must never cause.
     */
    @Override
    @CheckForNull
    protected EnvironmentExpander get(DelegatedContext context) throws IOException, InterruptedException {
        return guarded(() -> contribute(context));
    }

    /**
     * Runs a contribution attempt and swallows everything except an interruption.
     *
     * <p>{@link Throwable}, not {@link Exception}: a {@link LinkageError} or {@link NoClassDefFoundError}
     * after an upgrade would otherwise take out every step on the controller, not one build. Catching
     * {@link Error} is normally poor practice — here the alternative is failing deployments to protect a
     * JVM that is already unwell, and the plugin's contribution is optional by definition.
     *
     * <p>{@link InterruptedException} is re-thrown deliberately: an aborted build must stay aborted.
     *
     * <p>This path writes <b>nothing</b> to the build console. It is the last resort, and it is reached
     * in states where attempting console I/O could itself throw. Expected, diagnosable failures are
     * reported to the console one level in, by {@link #contribute}.
     */
    @CheckForNull
    static <T> T guarded(Attempt<T> attempt) throws InterruptedException {
        try {
            return attempt.run();
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable t) {
            // Benign races log at FINE, everything else at WARNING. This exists so that a log recorder on
            // this package is usable as an alerting channel: if routine end-of-step noise arrives at
            // WARNING with a stack trace, the signal an administrator actually needs — a node whose role
            // cannot be resolved, a contribution refused by the additions-only invariant — is buried in
            // it, and the recorder gets ignored. A monitoring channel that cries wolf is worse than none.
            Level level = isBenignRace(t) ? Level.FINE : Level.WARNING;
            LOGGER.log(
                    level,
                    t,
                    () ->
                            "aws-build-session-logger: contributing nothing; this build authenticates exactly as it would "
                                    + "have without the plugin");
            return null;
        }
    }

    /**
     * Whether a failure is Jenkins' own end-of-step race rather than a problem worth an operator's time.
     *
     * <p>{@code DefaultStepContext.getListener()} throws {@code IOException: cannot start writing logs to
     * a finished node} when dynamic context is recomputed for a step whose node has already completed.
     * Nothing is wrong and nothing is lost: the step is over, so there is no process left to consume the
     * environment. Observed repeatedly during a <em>successful</em> production deployment.
     *
     * <p>Matched on the message rather than the type, because the type is a bare {@link IOException} that
     * legitimate failures also use. Deliberately narrow — anything unrecognised stays at WARNING, so an
     * unknown failure is never silently downgraded.
     */
    private static boolean isBenignRace(Throwable t) {
        // Bounded, because this runs inside the fail-open guard. A cyclic cause chain is constructible
        // through the public API (a.initCause(b); b.initCause(a)), and an unbounded walk over one would
        // hang the build thread here — worse than any exception, since the build would then neither fail
        // nor proceed. The previous `c.getCause() == c` guard was dead code: getCause() returns null when
        // no cause is set, never this. A depth cap is what Throwable.printStackTrace does too.
        int depth = 0;
        for (Throwable c = t; c != null && depth < 20; c = c.getCause(), depth++) {
            String message = c.getMessage();
            if (message != null && message.contains("cannot start writing logs to a finished node")) {
                return true;
            }
        }
        return false;
    }

    /**
     * One attempt to contribute. Package-private so the guard can be tested directly.
     *
     * <p>Generic because Pipeline and Freestyle builds need different wrappers around the same
     * variables, and the fail-open guard is the last thing that should be duplicated between them.
     */
    @FunctionalInterface
    interface Attempt<T> {
        @CheckForNull
        T run() throws Exception;
    }

    @CheckForNull
    private EnvironmentExpander contribute(DelegatedContext context) throws IOException, InterruptedException {
        AwsBuildSessionConfiguration configuration = AwsBuildSessionConfiguration.get();
        if (configuration == null || !configuration.isManagedAuthentication()) {
            return null;
        }

        Run<?, ?> run = context.get(Run.class);
        if (run == null || !configuration.appliesTo(run.getParent().getFullName())) {
            return null;
        }

        // No workspace means no agent, which means no process that could consume this.
        FilePath current = context.get(FilePath.class);
        if (current == null) {
            return null;
        }

        Node node = context.get(Node.class);
        if (!configuration.appliesToNode(labelsOf(node))) {
            return null;
        }

        // buildWorkspace can return its own @CheckForNull argument, so its result is nullable to an
        // analyser even though `current` was checked above. Bind and re-check rather than suppress.
        FilePath workspace = buildWorkspace(current, node, run);
        if (workspace == null) {
            return null;
        }

        Map<String, String> variables = prepareOnce(
                run, workspace, node, context.get(Computer.class), configuration, context.get(TaskListener.class));
        if (variables == null) {
            return null;
        }

        // Observe only: everything above ran - the configuration was read, decorated, safety-checked and
        // written, and the console says what would have happened - but nothing is exported. A build in
        // this mode cannot observe the plugin, so it cannot be broken by it. See
        // AwsBuildSessionConfiguration#isObserveOnly for why this exists.
        if (configuration.isObserveOnly()) {
            return null;
        }

        // Contribute ALONGSIDE what the build already has, never instead of it.
        //
        // A DynamicContext that answers unconditionally short-circuits the walk to the enclosing
        // context level: ContextVariableSet.get scans the current level, then consults every
        // DynamicContext, and only then recurses to its parent. So an EnvironmentExpander published by
        // an enclosing withCredentials/withEnv/withAWS block is never reached the moment any inner
        // block - dir, ws, container - adds a context level of its own, and its bindings vanish with
        // no error. Observed in production: a job lost its artifact-repository credentials to exactly this,
        // while the same job run out of scope passed.
        //
        // Order is load-bearing: ours expands FIRST so anything the build set deliberately wins. This
        // plugin decorates the node's default, and a value a job chose is not a default.
        EnvironmentExpander ours = EnvironmentExpander.constant(variables);
        EnvironmentExpander existing = context.get(EnvironmentExpander.class);
        // merge() null-checks only its first argument; passing null as the second NPEs on expand.
        if (existing == null) {
            return ours;
        }
        EnvironmentExpander merged = EnvironmentExpander.merge(ours, existing);

        // ADDITIONS-ONLY INVARIANT, checked at runtime rather than argued from the merge order.
        //
        // The ordering above is correct by construction, but "correct by construction" is exactly the
        // claim that once failed: the guard only caught exceptions, and a contribution that
        // succeeds while quietly removing something throws nothing. The config-file surface has a runtime
        // check (AwsConfigOverlay#validate); this is its counterpart for the environment.
        //
        // It also covers the one thing merge ordering cannot: this depends on DelegatedContext#get
        // returning the enclosing expander faithfully. If it ever returns null, a partial view, or a
        // different level than expected - in a nesting shape nobody has written yet - the merge is
        // built from the wrong base and the ordering argument silently no longer holds. Comparing the
        // actual expansions needs no such assumption, so an unimagined shape fails safe instead of
        // failing silently. That is what makes future jobs safe without enumerating them.
        String loss = wouldRemoveSomething(existing, merged);
        if (loss != null) {
            warn(context.get(TaskListener.class), "not contributing: " + loss);
            LOGGER.log(Level.WARNING, "aws-build-session-logger declined to contribute: {0}", loss);
            // null means "this DynamicContext has no answer", so resolution continues to the enclosing
            // level and the build gets its own environment untouched. Losing attribution is acceptable;
            // changing a variable the build set is not.
            return null;
        }
        return merged;
    }

    /**
     * Whether contributing would remove or alter any variable this plugin does not own.
     *
     * <p>Both sides are expanded from an empty {@link EnvVars}, so the comparison is strictly "what
     * the enclosing environment sets" against "what the merged environment sets" - not a diff against
     * the whole build environment.
     *
     * <p>Every variable the enclosing block sets must survive unchanged - including the two this
     * plugin owns. There is no exemption for them: {@code merge(ours, existing)} expands ours first,
     * so for any key both sides set, the enclosing value is the one that survives. A job that sets its
     * own {@code AWS_CONFIG_FILE} keeps it, because a value a job chose is not a default.
     *
     * <p>Returns {@code null} when the contribution is purely additive, or a short description of the
     * first violation otherwise. Variable <em>names</em> appear in that description; <em>values</em>
     * never do, because an enclosing {@code withCredentials} expands secrets into this map.
     *
     * <p>Package-private so the invariant can be tested directly, without having to construct a
     * nesting shape that provokes a violation through a real pipeline.
     *
     * @param existing the environment published by the enclosing context level
     * @param merged   what this plugin proposes to publish instead
     */
    @CheckForNull
    static String wouldRemoveSomething(@NonNull EnvironmentExpander existing, @NonNull EnvironmentExpander merged)
            throws IOException, InterruptedException {
        EnvVars before = new EnvVars();
        existing.expand(before);
        EnvVars after = new EnvVars();
        merged.expand(after);
        for (Map.Entry<String, String> entry : before.entrySet()) {
            String name = entry.getKey();
            String now = after.get(name);
            if (now == null) {
                return "the enclosing block sets " + name + ", which this plugin's contribution would drop";
            }
            if (!now.equals(entry.getValue())) {
                return "the enclosing block sets " + name + ", which this plugin's contribution would change";
            }
        }
        return null;
    }

    /**
     * The workspace the generated file belongs to: the build's, not whatever directory a step is in.
     *
     * <p>{@link DelegatedContext#get} for a {@link FilePath} returns the <em>current</em> directory, so
     * inside {@code dir('application/service')} it is a directory in the middle of a checked-out source
     * tree. Writing there scatters {@code aws-build-session-logger/} through the source, prepares the same file once per
     * {@code dir} block, and - when a step changes into a directory outside the workspace - leaves a
     * directory on the agent that no workspace cleanup will ever reclaim.
     *
     * <p>The canonical workspace is used only when the current directory is genuinely inside it, which is
     * what keeps {@code ws('other')} correct: that allocates a workspace outside this job's, so it falls
     * back to the path it was given.
     *
     * <p><b>Concurrent builds are anchored too.</b> {@link Node#getWorkspaceFor} always answers
     * {@code …/job}, but a second simultaneous build of the same job runs in {@code …/job@2}, which is
     * not under {@code …/job}. Treating that as "outside the workspace" meant every concurrent build fell
     * back to the current directory and re-acquired all three problems above. {@link #concurrentRoot}
     * recognises the {@code job@N} form and anchors to it, so {@code …/job@2/app} prepares at
     * {@code …/job@2}. Suffixes that are not concurrent-build numbers — {@code job@tmp},
     * {@code job@script}, {@code job@2x} — are correctly rejected and still fall back.
     */
    @CheckForNull
    static FilePath buildWorkspace(@CheckForNull FilePath current, @CheckForNull Node node, Run<?, ?> run) {
        if (current == null || node == null || !(run.getParent() instanceof hudson.model.TopLevelItem)) {
            return current;
        }
        FilePath workspace = node.getWorkspaceFor((hudson.model.TopLevelItem) run.getParent());
        if (workspace == null) {
            return current;
        }
        String here = current.getRemote();
        String root = workspace.getRemote();
        if (here.equals(root) || here.startsWith(root + "/") || here.startsWith(root + "\\")) {
            return workspace;
        }
        // Concurrent builds. getWorkspaceFor always answers ".../job", but a second simultaneous build of
        // the same job runs in ".../job@2". Without this the path is not "inside" the root, so every
        // concurrent build fell back to the current directory — reintroducing, for exactly those builds,
        // the three problems this method exists to prevent: aws-build-session-logger/ written into the checked-out
        // source,
        // one preparation per dir() block, and directories the cleanup cannot reclaim.
        FilePath concurrent = concurrentRoot(current, here, root);
        return concurrent != null ? concurrent : current;
    }

    /**
     * The {@code job@N} root for a concurrent build, or {@code null} if this path is not one.
     *
     * <p>The {@code @} must be followed by digits and then either nothing or a separator, so a job whose
     * name merely contains {@code @} cannot be mistaken for a concurrent workspace.
     */
    @CheckForNull
    private static FilePath concurrentRoot(FilePath current, String here, String root) {
        if (!here.startsWith(root + "@")) {
            return null;
        }
        int i = root.length() + 1;
        int digits = i;
        while (digits < here.length() && Character.isDigit(here.charAt(digits))) {
            digits++;
        }
        if (digits == i) {
            return null; // "@" not followed by a number
        }
        if (digits < here.length() && here.charAt(digits) != '/' && here.charAt(digits) != '\\') {
            return null; // "@2x" — not a concurrent-build suffix
        }
        return new FilePath(current.getChannel(), here.substring(0, digits));
    }

    /**
     * Prepares the build's configuration at most once per build and workspace, and returns the variables.
     *
     * <p>Memoized because both callers ask repeatedly: Jenkins recomputes dynamic context on every step,
     * and an environment contributor runs every time a build computes its environment. Without this,
     * each query would rewrite the file. A second {@code node} block on another agent has a different
     * key and prepares its own copy, which is the intended behaviour.
     *
     * <p>Shared by the Pipeline and Freestyle paths so the two cannot drift.
     */
    @CheckForNull
    static Map<String, String> prepareOnce(
            Run<?, ?> run,
            FilePath workspace,
            @CheckForNull Node node,
            @CheckForNull Computer computer,
            AwsBuildSessionConfiguration configuration,
            @CheckForNull TaskListener listener)
            throws InterruptedException {

        String key = key(run, workspace);
        // Exactly one preparation per key, even when parallel branches sharing a workspace arrive at the
        // same instant. Both would otherwise miss the memo and write the same path concurrently, and a
        // third reader can then see a half-written file - which an AWS SDK rejects as malformed, failing
        // every call in that branch. putIfAbsent alone does not prevent this: it deduplicates the memo
        // entry after the fact, long after both writes have already happened.
        Object lock = LOCKS.computeIfAbsent(key, unused -> new Object());
        synchronized (lock) {
            Map<String, String> prepared = PREPARED.get(key);
            if (prepared != null && stillOnDisk(prepared, node)) {
                return prepared;
            }
            try {
                prepared = prepareVariables(run, workspace, node, computer, configuration, listener);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                // An expected, diagnosable failure: a disk or permission problem on one node must not
                // become an outage across every job. Reported to the console so the loss is visible.
                LOGGER.log(
                        Level.WARNING,
                        e,
                        () -> "aws-build-session-logger: could not decorate AWS configuration for " + run);
                warn(listener, "could not read or write AWS configuration (" + e.getMessage() + ")");
                PREPARED.remove(key);
                return null;
            }
            if (prepared == null) {
                PREPARED.remove(key);
                return null;
            }
            PREPARED.put(key, prepared);
            return prepared;
        }
    }

    /**
     * Whether the generated file is still where the memo says it is.
     *
     * <p>{@code cleanWs()} and {@code deleteDir()} in the middle of a build are ordinary idioms, and
     * either removes the generated file while the memo keeps handing out its path for every later step.
     * An AWS SDK reads a missing {@code AWS_CONFIG_FILE} as an <em>empty</em> configuration rather than
     * an error, so {@code --profile non_prod} then fails with "The config profile could not be found" -
     * nothing thrown, nothing logged, and a deployment that stops for a reason nobody can see.
     *
     * <p>One stat per step on an already-open channel, in exchange for turning a silent failure into a
     * regenerated file.
     */
    private static boolean stillOnDisk(Map<String, String> prepared, @CheckForNull Node node)
            throws InterruptedException {
        String path = prepared.get(CONFIG_VARIABLE);
        if (path == null || node == null) {
            return true;
        }
        try {
            FilePath file = node.createPath(path);
            return file != null && file.exists();
        } catch (IOException | RuntimeException e) {
            // An agent that cannot be reached cannot be repaired by regenerating. Keep what we have and
            // let the build fail on its own terms rather than losing the configuration to a transient.
            LOGGER.log(Level.FINE, e, () -> "aws-build-session-logger: could not confirm " + path + " still exists");
            return true;
        }
    }

    /**
     * Generates the build's AWS configuration and returns the variables that point at it.
     *
     * <p>Shared by both build types on purpose. Pipeline contributes these through an
     * {@link EnvironmentExpander}; Freestyle through an {@link hudson.model.Environment}. Only the
     * wrapper differs — the file, the decoration, the safety check and the cleanup record are one
     * implementation, so the two can never drift apart.
     *
     * @return the variables to export, or {@code null} to contribute nothing
     */
    @CheckForNull
    static Map<String, String> prepareVariables(
            Run<?, ?> run,
            FilePath workspace,
            @CheckForNull Node node,
            @CheckForNull Computer computer,
            AwsBuildSessionConfiguration configuration,
            @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {

        FilePath nodeConfigFile = locateNodeConfig(node, computer);
        String nodeConfig = "";
        if (nodeConfigFile != null && nodeConfigFile.exists()) {
            nodeConfig = nodeConfigFile.readToString();
        }

        List<AwsProfile> overrides = configuration.usableProfiles();
        if (nodeConfig.isEmpty() && overrides.isEmpty()) {
            // Nothing to decorate and nothing to add. Contributing an empty configuration could only
            // take capability away from the build, so contribute nothing at all.
            warn(listener, "no AWS configuration found on this node and no fallback profiles configured");
            return null;
        }

        String sessionName = SessionName.forBuild(run.getParent().getFullName(), run.getNumber())
                .value();
        AwsConfigOverlay.Result result = AwsConfigOverlay.describe(
                nodeConfig,
                sessionName,
                overrides,
                configuration.getCredentialSource(),
                unprofiledRoleArnFor(configuration, workspace, node, listener));
        String decorated = result.content();

        // The guard around this method catches exceptions; it cannot catch "produced a wrong file
        // successfully". Exporting such a file would fail every AWS call in the build with nothing
        // thrown. Verify the transform's own contract — additions only — and contribute nothing if it
        // does not hold.
        java.util.Optional<String> defect = AwsConfigOverlay.validate(nodeConfig, decorated);
        if (defect.isPresent()) {
            LOGGER.log(
                    Level.WARNING,
                    () -> "aws-build-session-logger: refusing to export a generated configuration for " + run + ": "
                            + defect.get());
            warn(listener, "generated AWS configuration failed its own safety check (" + defect.get() + ")");
            return null;
        }

        FilePath temporaryDirectory = WorkspaceList.tempDir(workspace);
        if (temporaryDirectory == null) {
            throw new IOException("no temporary directory is available for " + workspace);
        }
        FilePath directory = temporaryDirectory.child(DIRECTORY_NAME);
        directory.mkdirs();
        directory.chmod(0700);
        FilePath config = directory.child(CONFIG_FILE);
        config.write(decorated, "UTF-8");
        config.chmod(0600);

        ManagedAwsRecord.record(run, node, directory);

        if (listener != null) {
            listener.getLogger()
                    .println("[aws-build-session-logger] "
                            + (configuration.isObserveOnly()
                                    ? "OBSERVE ONLY, nothing exported: would decorate as session "
                                    : "AWS configuration decorated as session ")
                            + sessionName
                            + (nodeConfigFile == null ? "" : " (from " + nodeConfigFile.getRemote() + ")")
                            + (result.unprofiledAttributed() ? "; calls naming no profile are attributed too" : ""));
            if (configuration.isDiagnostics() || SystemProperties.getBoolean(DIAGNOSTICS_PROPERTY)) {
                diagnose(
                        listener,
                        computer,
                        nodeConfigFile,
                        nodeConfig,
                        result,
                        config,
                        sessionName,
                        configuration.isObserveOnly());
            }
        }

        // AWS_SHARED_CREDENTIALS_FILE is deliberately absent: see the class javadoc.
        return Map.of(
                CONFIG_VARIABLE,
                config.getRemote(),
                SESSION_VARIABLE,
                sessionName,
                ROLE_SESSION_NAME_VARIABLE,
                sessionName);
    }

    /**
     * Prints what was discovered and what was changed. Diagnostics only; never enabled by default.
     *
     * <p>Deliberately prints the <em>node's</em> AWS environment rather than the build's: what matters
     * when a build authenticates unexpectedly is what the plugin saw before it changed anything.
     */
    private static void diagnose(
            TaskListener listener,
            @CheckForNull Computer computer,
            @CheckForNull FilePath nodeConfigFile,
            String nodeConfig,
            AwsConfigOverlay.Result result,
            FilePath writtenTo,
            String sessionName,
            boolean observeOnly) {
        java.io.PrintStream out = listener.getLogger();
        // In observe-only these variables are NOT exported, so they must not be reported as
        // though they were. The headline line already says "nothing exported"; a diagnostics
        // block that then says "exported AWS_CONFIG_FILE: ..." contradicts it. That matters
        // because observe-only exists precisely so its output can be READ and trusted while
        // surveying every job before enforcing.
        String verb = observeOnly ? "would export" : "exported";
        out.println("[aws-build-session-logger] --- diagnostics ---");
        EnvVars environment = new EnvVars();
        if (computer != null) {
            try {
                environment = computer.getEnvironment();
            } catch (IOException | InterruptedException e) {
                out.println(
                        "[aws-build-session-logger]   node environment          : UNREADABLE (" + e.getMessage() + ")");
            }
        }
        for (String variable : List.of("HOME", "AWS_CONFIG_FILE", "AWS_SHARED_CREDENTIALS_FILE", "AWS_PROFILE")) {
            String value = environment.get(variable);
            out.println(
                    "[aws-build-session-logger]   node " + pad(variable) + ": " + (value == null ? "<unset>" : value));
        }
        out.println("[aws-build-session-logger]   " + pad("resolved config path") + ": "
                + (nodeConfigFile == null ? "<none: no HOME and no AWS_CONFIG_FILE>" : nodeConfigFile.getRemote()));
        out.println("[aws-build-session-logger]   " + pad("config read") + ": "
                + (nodeConfig.isEmpty() ? "empty or absent" : nodeConfig.length() + " bytes"));
        out.println("[aws-build-session-logger]   " + pad("sections found") + ": " + result.sectionsFound());
        out.println("[aws-build-session-logger]   " + pad("sections decorated") + ": " + result.sectionsDecorated());
        out.println("[aws-build-session-logger]   " + pad("sections appended") + ": " + result.sectionsAppended());
        out.println("[aws-build-session-logger]   " + pad("written to") + ": " + writtenTo.getRemote());
        out.println("[aws-build-session-logger]   " + pad(verb + " AWS_CONFIG_FILE") + ": " + writtenTo.getRemote());
        out.println("[aws-build-session-logger]   " + pad(verb + " AWS_BUILD_SESSION_NAME") + ": " + sessionName);
        out.println("[aws-build-session-logger]   " + pad(verb + " AWS_ROLE_SESSION_NAME") + ": " + sessionName);
        out.println("[aws-build-session-logger]   " + pad("AWS_SHARED_CREDENTIALS_FILE") + ": not set by this plugin");
        out.println("[aws-build-session-logger] --- end diagnostics ---");
    }

    private static String pad(String label) {
        return label.length() >= 30 ? label : label + " ".repeat(30 - label.length());
    }

    /**
     * The role the generated {@code [default]} should assume: a fixed ARN, or the node's own.
     *
     * <p>A single configured ARN is only correct while every agent shares one instance role. Hand a
     * node a {@code role_arn} it may not assume and its unprofiled {@code aws} calls <em>fail</em>
     * rather than merely going unattributed — the one way this feature can break a build, and it would
     * show up first on a node nobody tested. Resolving per node removes that failure mode for agents
     * that do not exist yet.
     *
     * <p>Fail-safe in both directions: an unresolvable node returns {@code null}, which means no
     * {@code [default]} is written and unprofiled calls are left exactly as the node had them.
     */
    @CheckForNull
    @SuppressWarnings("deprecation") // the fixed ARN is retained for XML back-compat and for tests
    static String unprofiledRoleArnFor(
            AwsBuildSessionConfiguration configuration,
            FilePath workspace,
            @CheckForNull Node node,
            @CheckForNull TaskListener listener)
            throws InterruptedException {
        if (!configuration.isAttributeUnprofiledAsNodeRole()) {
            return configuration.getUnprofiledRoleArn();
        }
        String nodeName = node == null ? "" : node.getNodeName();
        // Bound the cache. Ephemeral cloud agents get a fresh node name each time, so without a cap this
        // accumulates one permanent entry per agent ever provisioned. Clearing wholesale rather than
        // evicting one entry is deliberate: it costs one IMDS probe per live node afterwards, and this
        // only ever runs when unprofiled attribution is enabled.
        if (NODE_ROLE_ARNS.size() > MAX_CACHED_NODE_ROLES) {
            NODE_ROLE_ARNS.clear();
        }
        String cached = NODE_ROLE_ARNS.get(nodeName);
        if (cached != null) {
            if (!UNRESOLVABLE.equals(cached)) {
                return cached;
            }
            // Warn on every build, not only the one that discovered it. The cache is an optimisation;
            // "this build's unprofiled calls are not attributed" is true of each of them, and an operator
            // reading one build's console must be able to see it.
            warnUnresolvable(listener);
            return null;
        }
        String resolved;
        try {
            resolved = workspace.act(new ResolveNodeRoleArn());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            LOGGER.log(
                    Level.FINE,
                    e,
                    () -> "aws-build-session-logger: could not resolve the instance role of " + nodeName);
            resolved = null;
        }
        if (resolved == null) {
            // Cache the failure too. Without this every build on a node that is not EC2, has no instance
            // profile, or whose role may not assume itself pays the cost again: two IMDS timeouts plus up
            // to 30 seconds in the self-assume probe — and it is paid while holding the preparation lock,
            // so it blocks the first step of the node block and serialises parallel branches.
            NODE_ROLE_ARNS.put(nodeName, UNRESOLVABLE);
            warnUnresolvable(listener);
            return null;
        }
        NODE_ROLE_ARNS.put(nodeName, resolved);
        return resolved;
    }

    private static void warnUnresolvable(@CheckForNull TaskListener listener) {
        warn(
                listener,
                "this node's own instance role could not be resolved, or AWS will not permit it to "
                        + "assume itself, so calls naming no profile are left exactly as the node had them");
    }

    /**
     * Clears the per-node role cache. For tests: the map is static, so one test caching
     * {@link #UNRESOLVABLE} under a node name short-circuits every later test in the same JVM — which
     * would let a test pass while never executing the path it claims to cover.
     */
    static void forgetNodeRoles() {
        NODE_ROLE_ARNS.clear();
    }

    /**
     * Asks the node it runs on for its own instance role, over IMDSv2.
     *
     * <p>Runs on the agent, not the controller: the controller's own role is irrelevant, and an agent
     * cannot be asked about its identity from anywhere else. Returns {@code null} for every failure —
     * no instance profile, IMDS disabled, hop limit too low, not EC2 at all — because every one of
     * those means "leave unprofiled calls alone".
     */
    private static final class ResolveNodeRoleArn extends jenkins.MasterToSlaveFileCallable<String> {

        private static final long serialVersionUID = 1L;

        /** {@code arn:aws:iam::123456789012:instance-profile/name} — partition and account. */
        private static final java.util.regex.Pattern PROFILE_ARN =
                java.util.regex.Pattern.compile("arn:(aws[a-zA-Z-]*):iam::(\\d+):instance-profile/");

        private static final String IMDS = "http://169.254.169.254/latest/";

        @Override
        @CheckForNull
        public String invoke(java.io.File directory, hudson.remoting.VirtualChannel channel) {
            try {
                String token = token();
                String roleName = read(token, "meta-data/iam/security-credentials/");
                if (roleName == null || roleName.isBlank()) {
                    return null;
                }
                // The listing is one role name per line; an instance has exactly one.
                roleName = roleName.split("\\R")[0].trim();

                String info = read(token, "meta-data/iam/info");
                if (info == null) {
                    return null;
                }
                java.util.regex.Matcher matcher = PROFILE_ARN.matcher(info);
                if (!matcher.find()) {
                    return null;
                }
                // The instance profile carries the partition and account; the role NAME comes from the
                // credentials listing. A profile and its role often differ in name, so both are needed.
                String roleArn = "arn:" + matcher.group(1) + ":iam::" + matcher.group(2) + ":role/" + roleName;

                // Naming the right role is not enough: AWS must also PERMIT the role to assume itself,
                // which needs its own identity policy to allow sts:AssumeRole on its own ARN. If it does
                // not, writing role_arn would make every unprofiled `aws` call in the build fail with
                // AccessDenied — turning "not audited" into "broken", on a node nobody tested.
                //
                // So prove it before claiming it. One STS call per node, cached by the caller. A refusal
                // returns null, which means no [default] is written and unprofiled calls are left
                // exactly as the node had them: unattributed, and working.
                return canAssumeItself(roleArn) ? roleArn : null;
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * Whether this node may actually re-issue its own role. Runs {@code aws sts assume-role} on the
         * node, because that is the only authority on the answer: the trust policy, the role's identity
         * policy, SCPs and permission boundaries all get a vote, and simulating them is not the same as
         * being allowed.
         *
         * <p>The probe session name is deliberately not a {@code jk-} name — it is not a build.
         */
        private static boolean canAssumeItself(String roleArn) {
            Process process = null;
            try {
                String executable = System.getProperty("io.jenkins.plugins.awsbuildsessionlogger.awsExecutable", "aws");
                process = new ProcessBuilder(
                                executable,
                                "sts",
                                "assume-role",
                                "--role-arn",
                                roleArn,
                                "--role-session-name",
                                "aws-build-session-logger-probe",
                                "--duration-seconds",
                                "900",
                                "--query",
                                "Credentials.AccessKeyId",
                                "--output",
                                "text")
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return false;
                }
                if (process.exitValue() != 0) {
                    return false;
                }
                try (java.io.InputStream in = process.getInputStream()) {
                    String out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                    // An access key id, not an error message.
                    return out.startsWith("ASIA") || out.startsWith("AKIA");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException | RuntimeException e) {
                // No aws CLI on the node, or it could not be executed. Fail safe.
                return false;
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }

        private static String token() throws IOException {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(IMDS + "api/token").openConnection();
            c.setRequestMethod("PUT");
            c.setRequestProperty("X-aws-ec2-metadata-token-ttl-seconds", "60");
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            try (java.io.InputStream in = c.getInputStream()) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        }

        @CheckForNull
        private static String read(String token, String path) {
            try {
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(IMDS + path).openConnection();
                c.setRequestProperty("X-aws-ec2-metadata-token", token);
                c.setConnectTimeout(2000);
                c.setReadTimeout(2000);
                try (java.io.InputStream in = c.getInputStream()) {
                    return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException e) {
                // 404 (no instance profile), connect timeout (IMDS disabled or hop limit too low),
                // or not EC2 at all. Every one of them means "leave unprofiled calls alone".
                return null;
            }
        }
    }

    /**
     * Finds the AWS configuration the executing node itself would use.
     *
     * <p>Portable by construction: it asks the node for its own environment rather than assuming a user,
     * a path, or a cloud. An explicit {@code AWS_CONFIG_FILE} on the node wins, because a node that has
     * been told where its configuration lives has already answered this question; otherwise
     * {@code $HOME/.aws/config}, which is what every AWS SDK would have used.
     */
    @CheckForNull
    private static FilePath locateNodeConfig(@CheckForNull Node node, @CheckForNull Computer computer) {
        String override = SystemProperties.getString(CONFIG_PATH_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            return node == null ? null : node.createPath(override.trim());
        }
        if (node == null || computer == null) {
            return null;
        }
        EnvVars environment;
        try {
            environment = computer.getEnvironment();
        } catch (IOException | InterruptedException e) {
            LOGGER.log(Level.FINE, e, () -> "aws-build-session-logger: could not read the environment of " + computer);
            return null;
        }
        String explicit = environment.get("AWS_CONFIG_FILE");
        if (explicit != null && !explicit.trim().isEmpty()) {
            return node.createPath(explicit.trim());
        }
        String home = environment.get("HOME");
        if (home == null || home.trim().isEmpty()) {
            return null;
        }
        FilePath root = node.createPath(home.trim());
        return root == null ? null : root.child(DEFAULT_CONFIG_RELATIVE_PATH);
    }

    /**
     * The executing node's label strings.
     *
     * <p>The built-in node reports an empty label set, so a controller-run build is matched on its node
     * name instead — otherwise scoping by label could never include it, and the controller runs builds.
     */
    @NonNull
    private static java.util.Set<String> labelsOf(@CheckForNull Node node) {
        if (node == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> labels = new java.util.LinkedHashSet<>();
        node.getAssignedLabels().forEach(label -> labels.add(label.getName()));
        String name = node.getNodeName();
        labels.add(name.isEmpty() ? "built-in" : name);
        return labels;
    }

    private static void warn(@CheckForNull TaskListener listener, String detail) {
        if (listener != null) {
            listener.getLogger()
                    .println("[aws-build-session-logger] " + detail
                            + "; this build will authenticate exactly as it does today, without build attribution.");
        }
    }

    /** Drops every memo entry for a finished build. Called from {@link ManagedCleanupListener}. */
    static void forget(@NonNull Run<?, ?> run) {
        String prefix = run.getExternalizableId() + ' ';
        PREPARED.keySet().removeIf(key -> key.startsWith(prefix));
        // Leaving these would leak one monitor per node block of every build the controller ever ran.
        LOCKS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Visible for tests: how many builds currently hold prepared state. */
    static int preparedCount() {
        return PREPARED.size();
    }

    /** Visible for tests: the keys currently held, for leak diagnosis. */
    static List<String> preparedKeys() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(PREPARED.keySet()));
    }

    private static String key(Run<?, ?> run, FilePath workspace) {
        return run.getExternalizableId() + ' ' + workspace.getRemote();
    }
}
