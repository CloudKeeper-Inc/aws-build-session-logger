package io.jenkins.plugins.awsbuildsessionlogger.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * The Jenkins-owned {@code profile name -> role ARN} mapping, plus the
 * three switches that govern Managed Authentication.
 *
 * <p>Configurable through the global configuration UI or, preferably, through Configuration as Code:
 *
 * <pre>{@code
 * unclassified:
 *   awsBuildSessionLogger:
 *     managedAuthentication: true
 *     jobNamePattern: "uat/.*"
 *     credentialSource: "Ec2InstanceMetadata"
 *     profiles:
 *       - name: "non_prod"
 *         roleArn: "arn:aws:iam::123456789012:role/non_prod"
 *         region: "us-east-1"
 *       - name: "ops"          # no roleArn: use the agent's own identity
 * }</pre>
 *
 * <p>This is deliberately <em>not</em> {@code ~/.aws/config}. That file lives on the agent filesystem,
 * outside Jenkins' permission model, and is editable by anyone with agent or SSH access — so an
 * identity decision made there is a decision Jenkins does not control. Putting the mapping here means
 * changing it requires Jenkins admin permission and appears in a JCasC diff.
 *
 * <p>Resolution is exact-match and case-sensitive, and returns {@link Optional#empty()} for anything
 * unknown. Callers are expected to fail the build rather than fall back to a default: guessing an
 * identity is worse than refusing to pick one.
 */
@Extension
@Symbol("awsBuildSessionLogger")
public class AwsBuildSessionConfiguration extends GlobalConfiguration {

    /** The default base identity for an agent: an EC2 instance role reached over IMDS. */
    public static final String DEFAULT_CREDENTIAL_SOURCE = "Ec2InstanceMetadata";

    /**
     * The values botocore's {@code CanonicalNameCredentialSourcer} accepts for {@code credential_source}
     * — verified against botocore 1.42.65. Exposed as configuration rather than hardcoded so the plugin
     * is not tied to EC2 agents: a controller whose agents run on ECS/EKS, or whose base credentials
     * arrive in the environment, configures this once and everything else is identical.
     */
    public static final List<String> CREDENTIAL_SOURCES =
            List.of(DEFAULT_CREDENTIAL_SOURCE, "EcsContainer", "Environment");

    /**
     * Volatile, and only ever replaced wholesale — never mutated in place.
     *
     * <p>Build threads read this while a request thread may be saving the global configuration. Emptying
     * the list and refilling it left a window in which a build calling {@code resolve(name)} saw no
     * profiles at all and aborted with "No AWS profile named 'prod' is configured" — a phantom failure
     * that would never reproduce. Volatile also gives the reading thread a happens-before edge to the
     * {@link AwsProfile} objects themselves, whose fields are written by {@code @DataBoundSetter} after
     * construction; without it a build could observe a profile whose {@code mode} was still null.
     */
    private volatile List<AwsProfile> profiles = List.of();

    private volatile boolean managedAuthentication;

    @CheckForNull
    private volatile String jobNamePattern;

    @CheckForNull
    private volatile String jobNameExcludePattern;

    @CheckForNull
    private volatile String nodeLabelPattern;

    @CheckForNull
    private volatile String unprofiledRoleArn;

    private volatile boolean attributeUnprofiledAsNodeRole;

    private volatile boolean diagnostics;

    /**
     * Ships <b>on</b>, unlike every other setting.
     *
     * <p>It has no effect until {@link #isManagedAuthentication()} is also enabled — the master switch
     * is checked first, so a fresh install still does nothing at all. What this default decides is what
     * happens at the moment an administrator <em>does</em> turn the master switch on: with observe-only
     * already selected, that first action reports what would happen and exports nothing, instead of
     * changing the environment of every in-scope build at once. Enforcing then becomes a second,
     * deliberate click rather than a side effect of the first.
     */
    private volatile boolean observeOnly = true;

    @NonNull
    private volatile String credentialSource = DEFAULT_CREDENTIAL_SOURCE;

    private static final Logger LOGGER = Logger.getLogger(AwsBuildSessionConfiguration.class.getName());

    public AwsBuildSessionConfiguration() {
        load();
        warnAboutMalformedRoleArns(profiles);
    }

    /** @return the singleton, or {@code null} if Jenkins is not available (e.g. during shutdown). */
    @CheckForNull
    public static AwsBuildSessionConfiguration get() {
        return GlobalConfiguration.all().get(AwsBuildSessionConfiguration.class);
    }

    @NonNull
    public List<AwsProfile> getProfiles() {
        return new ArrayList<>(profiles);
    }

    @DataBoundSetter
    public void setProfiles(@CheckForNull List<AwsProfile> profiles) {
        // One immutable snapshot, published by a single volatile write. Readers see either the old list
        // or the new one, never a list being rebuilt.
        this.profiles = profiles == null ? List.of() : List.copyOf(profiles);
        warnAboutMalformedRoleArns(this.profiles);
        save();
    }

    /**
     * Logs a warning for every assume-role profile whose ARN does not look like an IAM role ARN.
     *
     * <p>Profiles are set only through configuration as code or XML, so no form validation ever sees
     * them. Without this, a typo such as {@code roleArn: "non_prod"} is accepted silently, written into
     * the generated configuration, and fails only when AWS rejects the call. A warning, not a rejection:
     * refusing to load would change which profiles a running controller has, and IAM remains the
     * authority on what is valid.
     */
    private static void warnAboutMalformedRoleArns(@CheckForNull List<AwsProfile> profiles) {
        for (String name : malformedRoleArns(profiles)) {
            LOGGER.warning(() -> "AWS profile '" + name + "' has a role ARN that does not look like an IAM role ARN "
                    + "(arn:<partition>:iam::<12-digit account>:role/<name>). Calls using it will fail in AWS.");
        }
    }

    /** @return the names of assume-role profiles whose role ARN fails {@link AwsProfile#looksLikeRoleArn}. */
    @NonNull
    static List<String> malformedRoleArns(@CheckForNull List<AwsProfile> profiles) {
        if (profiles == null) {
            return List.of();
        }
        return profiles.stream()
                .filter(profile -> profile != null && profile.hasRole())
                .filter(profile -> !AwsProfile.looksLikeRoleArn(profile.getRoleArn()))
                .map(AwsProfile::getName)
                .collect(Collectors.toList());
    }

    /**
     * The master switch. Ships <b>off</b>, so installing or upgrading the plugin changes no build's
     * behaviour until an administrator opts in — and so turning it off again is a configuration change
     * rather than a plugin rollback, which would need a Jenkins restart.
     */
    public boolean isManagedAuthentication() {
        return managedAuthentication;
    }

    @DataBoundSetter
    public void setManagedAuthentication(boolean managedAuthentication) {
        this.managedAuthentication = managedAuthentication;
        save();
    }

    /** Optional regular expression against a job's full name. Blank means every job. */
    @CheckForNull
    public String getJobNamePattern() {
        return jobNamePattern;
    }

    @DataBoundSetter
    public void setJobNamePattern(@CheckForNull String jobNamePattern) {
        String trimmed = jobNamePattern == null ? "" : jobNamePattern.trim();
        this.jobNamePattern = trimmed.isEmpty() ? null : trimmed;
        save();
    }

    /**
     * Jobs to exclude even when they match the include pattern. Blank excludes nothing.
     *
     * <p>Exists because the alternative — expressing "everything except this one job" as a
     * negative-lookahead over a controller's entire job list — is error-prone under incident
     * pressure, and its failure mode is silently matching nothing.
     */
    @CheckForNull
    public String getJobNameExcludePattern() {
        return jobNameExcludePattern;
    }

    @DataBoundSetter
    public void setJobNameExcludePattern(@CheckForNull String jobNameExcludePattern) {
        String trimmed = jobNameExcludePattern == null ? "" : jobNameExcludePattern.trim();
        this.jobNameExcludePattern = trimmed.isEmpty() ? null : trimmed;
        save();
    }

    /**
     * Optional regular expression matched against the executing node's labels. Blank means every node.
     *
     * <p><b>No longer exposed in the UI.</b> It was offered as a cheaper rollout axis than job names for
     * estates whose agents differ, but the cases it was meant for are handled structurally instead: a
     * node with no AWS configuration has nothing contributed to it, and a node whose role cannot assume
     * itself is detected and skipped. The form entry was removed to keep the scoping story to one
     * axis — job name.
     *
     * <p>The property is retained so existing configuration XML still loads and so the agent tests can
     * pin themselves to a single node.
     *
     * @deprecated scope by job name instead.
     */
    @Deprecated
    @CheckForNull
    public String getNodeLabelPattern() {
        return nodeLabelPattern;
    }

    /** @deprecated see {@link #getNodeLabelPattern()}. */
    @Deprecated
    @DataBoundSetter
    public void setNodeLabelPattern(@CheckForNull String nodeLabelPattern) {
        String trimmed = nodeLabelPattern == null ? "" : nodeLabelPattern.trim();
        this.nodeLabelPattern = trimmed.isEmpty() ? null : trimmed;
        save();
    }

    /**
     * Role ARN used to attribute AWS calls that name no profile. Blank leaves them exactly as they are.
     *
     * <p><b>No longer exposed in the UI, and should not be set.</b> A single ARN for the whole
     * controller is correct only while every agent shares one instance role, and a wrong value does not
     * merely go unattributed — it makes every bare {@code aws} call <em>fail</em>, on whichever node
     * happens to differ. A typo here would do exactly that.
     *
     * <p>Superseded by {@link #isAttributeUnprofiledAsNodeRole()}, which resolves each node's real role
     * over IMDS and proves the assume succeeds before using it. The form entry was removed so
     * nobody can type an ARN here; the property is retained so existing XML still loads, and because the
     * tests need a settable ARN to exercise the {@code [default]} emission (per-node resolution needs
     * real IMDS and cannot run under {@code JenkinsRule}).
     *
     * @deprecated tick <em>Attribute unprofiled calls as the node's own instance role</em> instead.
     */
    @Deprecated
    @CheckForNull
    public String getUnprofiledRoleArn() {
        return unprofiledRoleArn;
    }

    /** @deprecated see {@link #getUnprofiledRoleArn()}. */
    @Deprecated
    @DataBoundSetter
    public void setUnprofiledRoleArn(@CheckForNull String unprofiledRoleArn) {
        String trimmed = unprofiledRoleArn == null ? "" : unprofiledRoleArn.trim();
        this.unprofiledRoleArn = trimmed.isEmpty() ? null : trimmed;
        save();
    }

    /**
     * Resolve the unprofiled identity from each node itself rather than from a fixed ARN.
     *
     * <p>{@link #getUnprofiledRoleArn()} is a single value for the whole controller. That is correct
     * only while every agent shares one instance role. An agent added later with a
     * <em>different</em> role would be handed a {@code role_arn} it is not allowed to assume, and its
     * unprofiled {@code aws} calls would <b>fail</b> rather than merely go unattributed. That is the
     * one way this feature could break a build, and it would appear only on a node nobody tested.
     *
     * <p>With this set, the plugin asks each node for its own instance role over IMDS at preparation
     * time and uses that. Every node is then correct by construction, including nodes that do not
     * exist yet, and there is no value to keep in step with infrastructure changes.
     *
     * <p>Fail-safe: if the node has no instance profile, or IMDS cannot be read, the plugin adds no
     * {@code [default]} at all and unprofiled calls are left exactly as the node left them — the
     * behaviour before this feature existed.
     *
     * <p><b>What this still cannot check</b> is whether the node's role is <em>permitted</em> to assume
     * itself. Self-assume needs the role's own identity policy to allow {@code sts:AssumeRole} on its
     * own ARN; if it does not, unprofiled calls on that node will fail. Verify with
     * {@code aws iam simulate-principal-policy --policy-source-arn ROLE --action-names sts:AssumeRole
     * --resource-arns ROLE} before enabling this on a controller with mixed agent roles.
     */
    public boolean isAttributeUnprofiledAsNodeRole() {
        return attributeUnprofiledAsNodeRole;
    }

    @DataBoundSetter
    public void setAttributeUnprofiledAsNodeRole(boolean attributeUnprofiledAsNodeRole) {
        this.attributeUnprofiledAsNodeRole = attributeUnprofiledAsNodeRole;
        save();
    }

    /**
     * Prints what the plugin discovered to the build console.
     *
     * <p>Deliberately configuration rather than a system property: a system property needs a controller
     * restart to change, which means the one switch needed during an incident is the one that cannot be
     * thrown — and the workaround is building and deploying a diagnostic release, which has happened.
     */
    public boolean isDiagnostics() {
        return diagnostics;
    }

    @DataBoundSetter
    public void setDiagnostics(boolean diagnostics) {
        this.diagnostics = diagnostics;
        save();
    }

    /**
     * Prepares everything and exports nothing.
     *
     * <p>The reason this exists: an in-scope production build once failed because the plugin
     * <em>successfully</em> contributed something that displaced a credential binding. The fail-open
     * guard never fired, because nothing threw. A guard that only catches exceptions cannot catch a
     * correct-looking contribution that takes something away, and no amount of testing a plugin against
     * job shapes someone imagined will find the shape nobody imagined.
     *
     * <p>So: turn this on, widen the scope to everything, and let a day of real traffic run. Every build
     * reports the configuration it would have been given, the sections that would have been decorated
     * and any problem found — while exporting nothing at all, so a build cannot observe the plugin and
     * cannot be affected by it. Then read the evidence and turn it off.
     *
     * <p>This is the answer to "the master switch is the only safety net". It is not: the master switch
     * is what you reach for after something breaks. This is how you find out beforehand, across every
     * job that actually runs, without watching a queue all day.
     */
    public boolean isObserveOnly() {
        return observeOnly;
    }

    @DataBoundSetter
    public void setObserveOnly(boolean observeOnly) {
        this.observeOnly = observeOnly;
        save();
    }

    @NonNull
    public String getCredentialSource() {
        return credentialSource;
    }

    @DataBoundSetter
    public void setCredentialSource(@CheckForNull String credentialSource) {
        String trimmed = credentialSource == null ? "" : credentialSource.trim();
        this.credentialSource = CREDENTIAL_SOURCES.contains(trimmed) ? trimmed : DEFAULT_CREDENTIAL_SOURCE;
        save();
    }

    /**
     * Rebuilding from the submitted form rather than relying on setters alone: an unchecked checkbox
     * and a cleared text field are both <em>absent</em> from the JSON, so without this a value could
     * never be turned off again once set.
     *
     * <p><b>{@code profiles}, {@code nodeLabelPattern} and {@code unprofiledRoleArn} are deliberately NOT
     * reset here.</b>
     * They no longer appear on the form, so the submitted JSON never carries them; resetting them would
     * mean any admin pressing Save — even for an unrelated setting — silently erased a value that can
     * now only be set through configuration XML or JCasC. For {@code nodeLabelPattern} the erasure widens
     * scope from one agent to every node on the controller, which is the dangerous direction; for
     * {@code profiles} it would quietly discard an operator's JCasC-managed list the first time anyone
     * pressed Save for an unrelated reason. Only fields the form actually submits may be reset from the
     * form.
     */
    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        // NOTHING is reset before the bind. An unchecked checkbox and a cleared text field are both
        // ABSENT from the JSON, so a field the form no longer carries still has to be returned to its
        // default — but doing that BEFORE bindJSON published the default to every concurrently running
        // build, and two of these defaults fail OPEN:
        //
        //   jobNamePattern = null        -> blank pattern means EVERY job is in scope
        //   jobNameExcludePattern = null -> the job an admin excluded during an incident is back in scope
        //
        // So an admin pressing Save for an unrelated setting could, for a few milliseconds, put every job
        // on the controller into scope and drop the exclusion that was containing an incident. Recording
        // which keys arrived and applying defaults AFTER the bind means each field moves from its old
        // value straight to its new one, and no reader can observe the gap.
        boolean hasManaged = json.has("managedAuthentication");
        boolean hasInclude = json.has("jobNamePattern");
        boolean hasExclude = json.has("jobNameExcludePattern");
        boolean hasNodeRole = json.has("attributeUnprofiledAsNodeRole");
        boolean hasDiagnostics = json.has("diagnostics");
        boolean hasObserveOnly = json.has("observeOnly");
        boolean hasCredentialSource = json.has("credentialSource");

        req.bindJSON(this, json);

        if (!hasManaged) {
            managedAuthentication = false;
        }
        if (!hasInclude) {
            jobNamePattern = null;
        }
        if (!hasExclude) {
            jobNameExcludePattern = null;
        }
        if (!hasNodeRole) {
            attributeUnprofiledAsNodeRole = false;
        }
        if (!hasDiagnostics) {
            diagnostics = false;
        }
        if (!hasObserveOnly) {
            // true, matching the field initialiser — NOT false. Every other absent-default here is the
            // inert value; this is the one field where "inert" means ON. A checkbox always submits its
            // key, so unticking still works; this branch only guards a hand-built POST that carries the
            // master switch but omits this, where defaulting to enforcing would be the wrong direction.
            observeOnly = true;
        }
        if (!hasCredentialSource) {
            credentialSource = DEFAULT_CREDENTIAL_SOURCE;
        }
        save();
        return true;
    }

    /**
     * Resolves a profile name to its configured entry.
     *
     * @param name the profile name a pipeline asked for
     * @return the matching entry, or empty if there is no such profile. Entries without a usable name
     *     are treated as absent — a half-configured profile must not silently resolve to something.
     */
    @NonNull
    public Optional<AwsProfile> resolve(@CheckForNull String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return profiles.stream()
                .filter(AwsProfile::isComplete)
                .filter(profile -> profile.getName().equals(wanted))
                .findFirst();
    }

    /**
     * @return every entry that can be rendered into a generated AWS configuration file: a usable name,
     *     and whatever its declared mode requires. An assume-role profile with no ARN is deliberately
     *     excluded rather than downgraded to the agent's identity — a half-configured profile must
     *     never quietly authenticate as something else.
     */
    @NonNull
    public List<AwsProfile> usableProfiles() {
        return profiles.stream().filter(AwsProfile::isUsable).collect(Collectors.toList());
    }

    /** @return the configured, usable profile names, for error messages and form help. */
    @NonNull
    public List<String> configuredProfileNames() {
        return usableProfiles().stream().map(AwsProfile::getName).collect(Collectors.toList());
    }

    /**
     * @param jobFullName the job's full name, e.g. {@code uat/Backend/deploy}
     * @return whether Managed Authentication should apply to this job. Fails <em>closed on the
     *     pattern</em>: an unparseable regular expression matches nothing, so a typo narrows the
     *     rollout rather than silently widening it to the whole controller.
     */
    public boolean appliesTo(@CheckForNull String jobFullName) {
        if (!managedAuthentication || jobFullName == null) {
            return false;
        }
        // Include: absent means every job; unparseable matches nothing, so a typo narrows the rollout.
        if (!matches(jobNamePattern, jobFullName, true, false)) {
            return false;
        }
        // Exclude: evaluated after include and wins. Absent excludes nothing; unparseable also excludes
        // nothing, so a typo here cannot silently switch attribution off across the controller.
        return !matches(jobNameExcludePattern, jobFullName, false, false);
    }

    /**
     * @param labels the executing node's label strings; empty when the node is unknown
     * @return whether the node is in scope. A blank pattern means every node. Fails <em>closed</em> on an
     *     unparseable pattern, consistent with {@link #appliesTo}.
     */
    public boolean appliesToNode(@CheckForNull Collection<String> labels) {
        // Snapshot once. The field is volatile, so re-reading it could see a different value between the
        // null check and the compile — an admin saving the configuration mid-build. One read means this
        // decision is made against one consistent value.
        String pattern = nodeLabelPattern;
        if (pattern == null) {
            return true;
        }
        if (labels == null || labels.isEmpty()) {
            // A pattern was set and there is nothing to match it against: narrow rather than widen.
            return false;
        }
        try {
            Pattern compiled = Pattern.compile(pattern);
            return labels.stream().anyMatch(label -> compiled.matcher(label).matches());
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * Full-string match, as {@link java.util.regex.Matcher#matches()} rather than {@code find()}: a
     * pattern must describe the whole job name, so {@code deploy} does not match {@code folder/deploy}.
     *
     * <p>The two fallbacks are separate on purpose. "No pattern configured" and "the pattern does not
     * compile" are different situations, and collapsing them would make an invalid <em>include</em>
     * pattern behave like a blank one — silently widening the rollout to every job on a typo, which is
     * the one direction this must never fail in.
     *
     * @param whenAbsent result when no pattern is configured
     * @param whenUnparseable result when the pattern does not compile; always the narrower answer
     */
    private static boolean matches(
            @CheckForNull String pattern, String value, boolean whenAbsent, boolean whenUnparseable) {
        if (pattern == null) {
            return whenAbsent;
        }
        try {
            return Pattern.compile(pattern).matcher(value).matches();
        } catch (PatternSyntaxException e) {
            return whenUnparseable;
        }
    }

    public FormValidation doCheckJobNamePattern(@QueryParameter String value) {
        String pattern = value == null ? "" : value.trim();
        if (pattern.isEmpty()) {
            return FormValidation.warning("Blank: applies to EVERY Pipeline job on this controller.");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return FormValidation.error("Not a valid regular expression: " + e.getDescription());
        }
        return FormValidation.ok(describeMatches(pattern, "Applies to"));
    }

    public FormValidation doCheckJobNameExcludePattern(@QueryParameter String value) {
        String pattern = value == null ? "" : value.trim();
        if (pattern.isEmpty()) {
            return FormValidation.ok("Blank: nothing is excluded.");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return FormValidation.error("Not a valid regular expression: " + e.getDescription());
        }
        return FormValidation.ok(describeMatches(pattern, "Excludes"));
    }

    public FormValidation doCheckNodeLabelPattern(@QueryParameter String value) {
        String pattern = value == null ? "" : value.trim();
        if (pattern.isEmpty()) {
            return FormValidation.ok("Blank: applies on every node.");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return FormValidation.error("Not a valid regular expression: " + e.getDescription());
        }
        return FormValidation.ok("Matched against each of the executing node's labels, in full.");
    }

    /**
     * Validates the unprofiled-identity ARN, and warns when it is blank.
     *
     * <p>Blank is a legitimate, safe choice — calls naming no profile keep behaving exactly as they do
     * today — so this is a warning rather than an error, but it is worth surfacing because "attribution
     * is on" and "everything is attributed" are not the same thing.
     */
    public FormValidation doCheckUnprofiledRoleArn(@QueryParameter String value) {
        String arn = value == null ? "" : value.trim();
        if (arn.isEmpty()) {
            return FormValidation.warning(
                    "Blank: calls that name no profile stay unattributed, exactly as they are today.");
        }
        if (!AwsProfile.looksLikeRoleArn(arn)) {
            return FormValidation.error("Expected an IAM role ARN, e.g. arn:aws:iam::123456789012:role/name");
        }
        return FormValidation.ok("Calls naming no profile will assume this role under the build's session "
                + "name. Use the agent's own instance role: a different role changes the principal ARN, "
                + "and resource policies grant by principal ARN.");
    }

    /** Reports how many of this controller's current jobs a pattern selects, so blast radius is visible. */
    private static String describeMatches(String pattern, String verb) {
        Jenkins instance = Jenkins.getInstanceOrNull();
        if (instance == null) {
            return verb + " jobs whose full name matches, e.g. uat/Backend/deploy.";
        }
        Pattern compiled = Pattern.compile(pattern);
        List<String> matched = instance.getAllItems(Job.class).stream()
                .map(Job::getFullName)
                .filter(name -> compiled.matcher(name).matches())
                .sorted()
                .collect(Collectors.toList());
        if (matched.isEmpty()) {
            return verb + " no job that currently exists. Check the pattern: it is matched against the "
                    + "job's FULL name, e.g. uat/Backend/deploy.";
        }
        String preview = matched.stream().limit(5).collect(Collectors.joining(", "));
        return verb + " " + matched.size() + " current job" + (matched.size() == 1 ? "" : "s") + ": " + preview
                + (matched.size() > 5 ? ", …" : "");
    }

    /** Populates the credential-source dropdown. Values, not labels — they go into the file verbatim. */
    public hudson.util.ListBoxModel doFillCredentialSourceItems() {
        hudson.util.ListBoxModel items = new hudson.util.ListBoxModel();
        for (String source : CREDENTIAL_SOURCES) {
            items.add(source, source);
        }
        return items;
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "AWS Build Session Logger";
    }
}
