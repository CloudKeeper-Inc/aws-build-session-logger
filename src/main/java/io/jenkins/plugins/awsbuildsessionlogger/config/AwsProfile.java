package io.jenkins.plugins.awsbuildsessionlogger.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * One entry in the Jenkins-owned {@code profile name -> role ARN} mapping.
 *
 * <p>A profile name is an opaque label chosen by the Jenkins administrator. This class attaches no
 * meaning to any particular value: {@code prod}, {@code non_prod}, {@code team-a-sandbox} and
 * {@code anything} are all equally valid, and nothing in the plugin branches on what a name is. That
 * is what keeps the plugin free of organization-specific knowledge — the organization's vocabulary
 * lives in its JCasC file, not in this source tree.
 *
 * <p><b>A profile declares its authentication mode explicitly.</b> {@link #ASSUME_ROLE} means "assume
 * this role, under this build's session name" — the cross-account case, and the only one that can
 * carry {@code jk-<job>-<build>} into CloudTrail, because it is the only one that performs an
 * AssumeRole. {@link #INSTANCE_PROFILE} means "use the agent's own identity" — the same-account case,
 * where there is nothing to assume.
 *
 * <p>The mode is declared rather than inferred from whether a role ARN happens to be blank. Inference
 * would turn a mistyped or deleted ARN into a silent downgrade to "no authentication", which is
 * precisely the failure this plugin exists to prevent. Declared, the same mistake is a form error.
 *
 * <p>Role ARNs are deliberately <em>not</em> secrets and are not routed through the Credentials
 * plugin. Keeping them in global configuration means changing one requires Jenkins admin permission
 * and shows up in a JCasC diff, which is the actual control we want.
 */
public final class AwsProfile extends AbstractDescribableImpl<AwsProfile> {

    /**
     * Characters that would break, or allow injection into, the generated AWS configuration file.
     * A profile name becomes an INI section header ({@code [profile <name>]}), so a bracket or a
     * newline could introduce arbitrary configuration keys. Whitespace is rejected separately because
     * botocore's own config parser cannot read {@code [profile with space]} at all — verified against
     * botocore 1.42.65.
     */
    private static final Pattern UNSAFE_IN_NAME = Pattern.compile("[\\[\\]\\r\\n]|\\s");

    /** A role ARN is a value, not a section header, so only line breaks can inject a new key. */
    private static final Pattern UNSAFE_IN_VALUE = Pattern.compile("[\\r\\n]");

    /** {@code arn:<partition>:iam::<account>:role/<path and name>}. */
    private static final Pattern ROLE_ARN_SHAPE = Pattern.compile("^arn:[a-z0-9-]+:iam::\\d{12}:role/.+$");

    /** Assume the configured role under this build's session name. The cross-account case. */
    public static final String ASSUME_ROLE = "AssumeRole";

    /** Use the agent's own identity. The same-account case, where there is nothing to assume. */
    public static final String INSTANCE_PROFILE = "InstanceProfile";

    public static final List<String> MODES = List.of(ASSUME_ROLE, INSTANCE_PROFILE);

    private final String name;

    @CheckForNull
    private final String roleArn;

    /**
     * How a build authenticates under this profile. Nullable in the field, never in the getter:
     * configuration saved before this option existed has no value, and every such profile was an
     * {@link #ASSUME_ROLE} one, so that is what absence means.
     */
    @CheckForNull
    private String mode;

    /** Optional. Written into the generated profile, and exported by the explicit block step. */
    @CheckForNull
    private String region;

    @DataBoundConstructor
    public AwsProfile(String name, @CheckForNull String roleArn) {
        this.name = trimToEmpty(name);
        this.roleArn = trimToNull(roleArn);
    }

    @NonNull
    public String getName() {
        return name;
    }

    /** @return {@link #ASSUME_ROLE} or {@link #INSTANCE_PROFILE}; never null. */
    @NonNull
    public String getMode() {
        return INSTANCE_PROFILE.equals(mode) ? INSTANCE_PROFILE : ASSUME_ROLE;
    }

    @DataBoundSetter
    public void setMode(@CheckForNull String mode) {
        String trimmed = trimToEmpty(mode);
        this.mode = MODES.contains(trimmed) ? trimmed : null;
    }

    /** @return the role to assume, or {@code null} when this profile uses the agent's own identity. */
    @CheckForNull
    public String getRoleArn() {
        return roleArn;
    }

    @CheckForNull
    public String getRegion() {
        return region;
    }

    @DataBoundSetter
    public void setRegion(@CheckForNull String region) {
        this.region = trimToNull(region);
    }

    /**
     * @return {@code true} if this entry is usable at all, i.e. it has a name that can be rendered
     *     into a configuration file. A missing role ARN does <em>not</em> make an entry unusable —
     *     see the class javadoc.
     */
    public boolean isComplete() {
        return !name.isEmpty() && !UNSAFE_IN_NAME.matcher(name).find();
    }

    /**
     * @return {@code true} if this profile assumes a role, rather than using the agent's identity.
     *     Both the declared mode and a usable ARN are required: a profile declaring
     *     {@link #ASSUME_ROLE} with nothing to assume must not silently become an instance-profile
     *     one, so it renders as neither. The ARN's shape is not checked here; a malformed one is
     *     logged at load time by {@code AwsBuildSessionConfiguration}.
     */
    public boolean hasRole() {
        return ASSUME_ROLE.equals(getMode())
                && roleArn != null
                && !UNSAFE_IN_VALUE.matcher(roleArn).find();
    }

    /** @return {@code true} if this profile is usable at all under its declared mode. */
    public boolean isUsable() {
        return isComplete() && (INSTANCE_PROFILE.equals(getMode()) || hasRole());
    }

    /**
     * Shape check for an IAM role ARN, shared by every place that accepts one.
     *
     * <p>Deliberately permissive about the partition and the role path: {@code aws}, {@code aws-cn} and
     * {@code aws-us-gov} are all valid, and a role may sit under a path. The point is to catch an
     * obviously wrong value in the form — a profile name, a bare role name, a user ARN — not to
     * re-implement IAM's own validation, which will reject anything malformed at assume time anyway.
     */
    public static boolean looksLikeRoleArn(@CheckForNull String value) {
        String arn = trimToEmpty(value);
        return !arn.isEmpty()
                && !UNSAFE_IN_VALUE.matcher(arn).find()
                && ROLE_ARN_SHAPE.matcher(arn).matches();
    }

    private static String trimToEmpty(@CheckForNull String value) {
        return value == null ? "" : value.trim();
    }

    @CheckForNull
    private static String trimToNull(@CheckForNull String value) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AwsProfile)) {
            return false;
        }
        AwsProfile that = (AwsProfile) o;
        return name.equals(that.name)
                && getMode().equals(that.getMode())
                && Objects.equals(roleArn, that.roleArn)
                && Objects.equals(region, that.region);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, getMode(), roleArn, region);
    }

    /** Never includes anything sensitive — a role ARN is not a secret, and there is nothing else here. */
    @Override
    public String toString() {
        return "AwsProfile{name=" + name + ", mode=" + getMode() + ", roleArn=" + roleArn + ", region=" + region + "}";
    }

    @Extension
    @Symbol("awsProfile")
    public static final class DescriptorImpl extends Descriptor<AwsProfile> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "AWS profile";
        }

        public FormValidation doCheckName(@QueryParameter String value) {
            String profileName = value == null ? "" : value.trim();
            if (profileName.isEmpty()) {
                return FormValidation.error("A profile name is required. This is the name pipelines already pass to "
                        + "'aws --profile <name>' or boto3 Session(profile_name='<name>').");
            }
            if (UNSAFE_IN_NAME.matcher(profileName).find()) {
                return FormValidation.error("A profile name cannot contain whitespace, '[' or ']'. "
                        + "The AWS config parser cannot read such a name.");
            }
            return FormValidation.ok();
        }

        /** Populates the mode dropdown. */
        public hudson.util.ListBoxModel doFillModeItems() {
            hudson.util.ListBoxModel items = new hudson.util.ListBoxModel();
            items.add("Assume role (cross-account; audited as jk-<job>-<build>)", ASSUME_ROLE);
            items.add("Instance profile (same-account; the agent's own identity)", INSTANCE_PROFILE);
            return items;
        }

        /**
         * Whether an ARN is <em>required</em> depends on the declared mode, which is why the mode is a
         * query parameter here. Where the shape is concerned this stays advisory: the shape of an ARN
         * is AWS's business, not this plugin's, so a partition or format we have not anticipated is a
         * warning rather than a rejection.
         */
        public FormValidation doCheckRoleArn(@QueryParameter String value, @QueryParameter String mode) {
            String arn = value == null ? "" : value.trim();
            boolean instanceProfile = INSTANCE_PROFILE.equals(mode == null ? "" : mode.trim());

            if (instanceProfile) {
                return arn.isEmpty()
                        ? FormValidation.ok("Not needed in instance-profile mode.")
                        : FormValidation.warning("Ignored in instance-profile mode: builds run as the "
                                + "agent's own identity. Switch to assume-role mode to use this ARN.");
            }
            if (arn.isEmpty()) {
                return FormValidation.error("A role ARN is required in assume-role mode, e.g. "
                        + "arn:aws:iam::123456789012:role/example. Switch to instance-profile mode if "
                        + "builds should use the agent's own identity.");
            }
            if (UNSAFE_IN_VALUE.matcher(arn).find()) {
                return FormValidation.error("A role ARN cannot contain a line break.");
            }
            if (!arn.startsWith("arn:")) {
                return FormValidation.warning("This does not look like an ARN. Expected something like "
                        + "arn:aws:iam::123456789012:role/example");
            }
            return FormValidation.ok();
        }
    }
}
