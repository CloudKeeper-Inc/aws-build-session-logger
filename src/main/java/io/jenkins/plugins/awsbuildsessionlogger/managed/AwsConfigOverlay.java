package io.jenkins.plugins.awsbuildsessionlogger.managed;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.awsbuildsessionlogger.config.AwsProfile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces the build's AWS configuration by <b>decorating</b> the executing node's own configuration,
 * never by replacing it.
 *
 * <p>The plugin is not an authentication provider. Whatever the node's configuration already says —
 * which role, which base identity, which region, which chained profile — is left exactly as it is. The
 * single thing added is {@code role_session_name}, and only on profiles that assume a role and do not
 * already pin one. That is the only difference a build can observe, and the only difference intended.
 *
 * <p><b>Line-based, deliberately.</b> Parsing an INI and regenerating it silently drops comments,
 * reorders keys, and mangles sections the parser does not model — {@code [sso-session …]},
 * {@code [services …]}, nested sub-sections, and whatever AWS adds next. Here everything is copied
 * verbatim and the transform only ever <em>inserts</em> lines, so a diff between input and output
 * contains additions and nothing else. The tests assert exactly that.
 *
 * <p><b>Two rules that exist because breaking them would break builds.</b>
 *
 * <ol>
 *   <li>A profile that already carries {@code role_session_name} is left untouched: an administrator
 *       pinned it deliberately, and overriding a deliberate decision is worse than losing attribution.
 *   <li>Jenkins-configured profiles are <em>appended only when the node does not define them</em>. The
 *       node is the source of truth; Jenkins is the fallback for what the node does not know about.
 * </ol>
 */
public final class AwsConfigOverlay {

    /** A section header: {@code [default]}, {@code [profile x]}, {@code [sso-session y]}, anything. */
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([^\\]]*)\\]\\s*$");

    /**
     * A {@code key = value} or {@code key: value} line, capturing its indentation.
     *
     * <p><b>Both delimiters matter.</b> configparser's defaults are {@code ('=', ':')} and botocore does
     * not override them, so {@code sso_session: corp} is a real key. Matching only {@code =} left
     * colon-delimited profiles invisible to every check in this class — including the identity guard, so
     * an SSO profile written with colons would still have been given the assume-role triple.
     *
     * <p>Indentation is captured rather than banned. See {@link #optionKeysOf}.
     */
    private static final Pattern ASSIGNMENT = Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+)\\s*[=:].*$");

    /**
     * The option keys a section declares, applying configparser's actual continuation rule.
     *
     * <p>An indented line is a continuation of the previous option <b>only when its indent exceeds that
     * option's</b> — not whenever it is indented at all. Both of these are legal and both occur:
     *
     * <pre>
     * [profile ops]            [services local]
     *     role_arn = arn:…     dynamodb =
     *     credential_source =    endpoint_url = http://localhost:8000
     *                          s3 =
     *                            endpoint_url = http://localhost:9000
     * </pre>
     *
     * <p>On the left every key is uniformly indented and all of them are real; on the right the indented
     * lines are continuations and {@code endpoint_url} is not a key of the section at all. Getting this
     * wrong is dangerous in both directions, and both directions have bitten this plugin:
     *
     * <ul>
     *   <li>Treating indented lines as keys sees {@code endpoint_url} twice, so the duplicate-key guard
     *       rejects a file botocore parses happily and the node loses all attribution.
     *   <li>Treating them as continuations misses {@code role_arn} on the left, so the section looks like
     *       it assumes nothing, gets the assume-role triple appended, and the file then really does
     *       declare {@code role_arn} twice — {@code DuplicateOptionError}, and <em>every</em> AWS call in
     *       the build fails, not just that profile's.
     * </ul>
     *
     * <p>This is the single parser for both {@link #describe} and {@link #duplicateKey}: when the two
     * disagreed, the guard was blind to exactly the corruption the writer produced.
     *
     * @param lines the section's lines, header included if present
     * @return each option key, lower-cased, in declaration order
     */
    private static List<String> optionKeysOf(List<String> lines) {
        List<String> keys = new ArrayList<>();
        int optionIndent = -1;
        for (String line : lines) {
            if (line.trim().isEmpty() || SECTION.matcher(line).matches()) {
                continue;
            }
            Matcher assignment = ASSIGNMENT.matcher(line);
            if (!assignment.matches()) {
                continue;
            }
            int indent = assignment.group(1).length();
            if (optionIndent >= 0 && indent > optionIndent) {
                continue; // a continuation of the option above, not a key of this section
            }
            optionIndent = indent;
            keys.add(assignment.group(2).toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    private static final String ROLE_ARN = "role_arn";
    private static final String ROLE_SESSION_NAME = "role_session_name";
    private static final String CREDENTIAL_SOURCE = "credential_source";

    /** The section consulted when a caller names no profile. */
    private static final String DEFAULT_SECTION = "default";

    private AwsConfigOverlay() {}

    /**
     * @param nodeConfig the executing node's AWS configuration, verbatim; may be empty
     * @param sessionName the build's {@code jk-<job>-<build>} session name
     * @param overrides profiles configured in Jenkins, appended only where the node is silent
     * @param credentialSource base-identity keyword, used for appended overrides only
     * @return the decorated configuration
     */
    @NonNull
    public static String apply(
            @NonNull String nodeConfig,
            @NonNull String sessionName,
            @NonNull List<AwsProfile> overrides,
            @NonNull String credentialSource) {
        return describe(nodeConfig, sessionName, overrides, credentialSource).content();
    }

    /**
     * As {@link #apply}, but also reporting what was found and what was changed.
     *
     * <p>Exists for diagnostics: when a build authenticates as something unexpected, the first question
     * is always "what did the plugin actually see in this node's configuration, and what did it touch?"
     * Guessing at that from the outside is what makes such an investigation slow.
     */
    @NonNull
    public static Result describe(
            @NonNull String nodeConfig,
            @NonNull String sessionName,
            @NonNull List<AwsProfile> overrides,
            @NonNull String credentialSource) {
        return describe(nodeConfig, sessionName, overrides, credentialSource, null);
    }

    /**
     * As above, additionally attributing calls that name no profile.
     *
     * @param unprofiledRoleArn role the generated {@code [default]} should assume, or {@code null} to
     *     leave unprofiled calls exactly as the node left them
     */
    @NonNull
    public static Result describe(
            @NonNull String nodeConfig,
            @NonNull String sessionName,
            @NonNull List<AwsProfile> overrides,
            @NonNull String credentialSource,
            @CheckForNull String unprofiledRoleArn) {

        List<String> lines = split(nodeConfig);
        List<String> out = new ArrayList<>(lines.size() + overrides.size() * 7);
        Set<String> present = new LinkedHashSet<>();
        List<String> decorated = new ArrayList<>();
        List<String> appended = new ArrayList<>();
        String currentSection = null;

        int start = 0;
        boolean inSection = false;
        boolean sawDefault = false;
        boolean defaultAttributed = false;

        for (int i = 0; i < lines.size(); i++) {
            Matcher header = SECTION.matcher(lines.get(i));
            if (header.matches()) {
                Emission emission = emissionForSlice(
                        lines.subList(start, i), currentSection, inSection, unprofiledRoleArn, credentialSource);
                if (emission.decorates() && currentSection != null) {
                    decorated.add(currentSection);
                    defaultAttributed |= DEFAULT_SECTION.equals(currentSection);
                }
                emit(out, lines.subList(start, i), emission, sessionName);
                currentSection = header.group(1).trim();
                present.add(currentSection);
                sawDefault |= DEFAULT_SECTION.equals(currentSection);
                inSection = true;
                start = i;
            }
        }
        Emission last = emissionForSlice(
                lines.subList(start, lines.size()), currentSection, inSection, unprofiledRoleArn, credentialSource);
        if (last.decorates() && currentSection != null) {
            decorated.add(currentSection);
            defaultAttributed |= DEFAULT_SECTION.equals(currentSection);
        }
        emit(out, lines.subList(start, lines.size()), last, sessionName);

        List<String> sectionsFound = new ArrayList<>(present);
        appendOverrides(out, present, overrides, sessionName, credentialSource, appended);

        // A node with no [default] at all — a real shape in the wild — still needs one if unprofiled
        // calls are to be attributed. Appending is the only way to reach them.
        if (unprofiledRoleArn != null && !sawDefault) {
            out.add("");
            out.add("# Added by the aws-build-session-logger Jenkins plugin: attributes calls that name no profile.");
            out.add("[" + DEFAULT_SECTION + "]");
            out.add(ROLE_ARN + " = " + unprofiledRoleArn);
            out.add(CREDENTIAL_SOURCE + " = " + credentialSource);
            out.add(ROLE_SESSION_NAME + " = " + sessionName);
            appended.add(DEFAULT_SECTION);
            defaultAttributed = true;
        }

        String content = String.join("\n", out);
        return new Result(
                content.isEmpty() || content.endsWith("\n") ? content : content + "\n",
                sectionsFound,
                decorated,
                appended,
                defaultAttributed);
    }

    /**
     * Decides what a section needs, which is one of three things.
     *
     * <p>A section that already assumes a role needs only a session name. The {@code [default]} section
     * of a node that does <em>not</em> assume a role needs the whole assume-role triple, because that is
     * what turns an unattributable base-identity call into a named session. Everything else is copied
     * untouched.
     *
     * <p>A pinned {@code role_session_name} always wins: an administrator set it deliberately, and
     * overriding a deliberate decision is worse than losing attribution.
     *
     * <p>The emission is decided from the section's own lines, using {@link #optionKeysOf} as the single source
     * of truth for what the section declares. Deriving {@code role_arn} / {@code role_session_name}
     * presence here — rather than accumulating it line by line while scanning — is what keeps the
     * writer and the duplicate-key guard from disagreeing about the same file.
     */
    private static Emission emissionForSlice(
            List<String> sectionLines,
            @CheckForNull String section,
            boolean inSection,
            @CheckForNull String unprofiledRoleArn,
            String credentialSource) {
        List<String> keys = inSection ? optionKeysOf(sectionLines) : List.of();
        return emissionFor(
                section,
                inSection,
                keys.contains(ROLE_ARN),
                keys.contains(ROLE_SESSION_NAME),
                unprofiledRoleArn,
                credentialSource,
                new LinkedHashSet<>(keys));
    }

    private static Emission emissionFor(
            @CheckForNull String section,
            boolean inSection,
            boolean assumesRole,
            boolean pinned,
            @CheckForNull String unprofiledRoleArn,
            String credentialSource,
            Set<String> sectionKeys) {
        if (!inSection || pinned) {
            return Emission.verbatim();
        }
        if (assumesRole) {
            return Emission.sessionNameOnly();
        }
        // A profile with no role_arn AND no other source of identity does not assume anything: it hands
        // the build the agent's base identity directly, whose session name the platform fixed and nobody
        // can change. That is the same unattributable path as [default], just reached by name instead of
        // by omission, so it gets the same treatment. The principal ARN is unchanged — it is the agent's
        // own role — so permissions, and every resource policy that grants to that role, are unaffected.
        if (unprofiledRoleArn != null && isProfileSection(section) && !establishesIdentity(sectionKeys)) {
            // Only what the section does not already declare. A section such as [profile ops] already
            // carries credential_source; writing it a second time makes the ENTIRE file unparseable to
            // botocore — every profile in it, not just this one — and every AWS call in the build then
            // fails. Measured in production, on the first run with unprofiled attribution enabled.
            return Emission.assumeRole(unprofiledRoleArn, credentialSource, sectionKeys);
        }
        return Emission.verbatim();
    }

    /**
     * Keys by which a profile establishes an identity <em>other</em> than the agent's base credentials.
     *
     * <p>"Declares no {@code role_arn}" is not the same as "uses the agent's base identity". A profile
     * can resolve credentials through SSO, through another profile, through an external process, through
     * static keys or through web identity — and in every one of those cases writing the assume-role
     * triple is wrong, in three distinct ways:
     *
     * <ul>
     *   <li><b>SSO</b> — botocore's assume-role provider takes precedence over the SSO provider, so the
     *       profile would silently authenticate as the agent's instance role, in the wrong account,
     *       instead of as the SSO identity the pipeline asked for. It would not fail; it would succeed
     *       as the wrong principal, which is worse.
     *   <li><b>{@code source_profile}</b> — botocore raises {@code InvalidConfigError} for a profile
     *       carrying both {@code source_profile} and {@code credential_source}, so every call using it
     *       fails outright.
     *   <li><b>{@code credential_process} / static keys / web identity</b> — the configured identity is
     *       silently replaced by the node role.
     * </ul>
     *
     * <p>None of these is caught by the duplicate-key guard, because the keys differ. The rule is
     * therefore the conservative one this plugin applies everywhere else: touch a section only when it
     * is unambiguous that doing so cannot change who the build is.
     */
    private static final Set<String> IDENTITY_KEYS = Set.of(
            "sso_session",
            "sso_start_url",
            "sso_region",
            "sso_account_id",
            "sso_role_name",
            "source_profile",
            "credential_process",
            "aws_access_key_id",
            "aws_secret_access_key",
            "aws_session_token",
            "web_identity_token_file");

    /** Whether the section already resolves credentials some way other than the agent's base identity. */
    private static boolean establishesIdentity(Set<String> sectionKeys) {
        for (String key : sectionKeys) {
            if (IDENTITY_KEYS.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** What to insert into a section: nothing, a session name, or a full assume-role triple. */
    private static final class Emission {

        private final boolean sessionName;

        @CheckForNull
        private final String roleArn;

        @CheckForNull
        private final String credentialSource;

        private final Set<String> alreadyPresent;

        private Emission(
                boolean sessionName,
                @CheckForNull String roleArn,
                @CheckForNull String credentialSource,
                Set<String> alreadyPresent) {
            this.sessionName = sessionName;
            this.roleArn = roleArn;
            this.credentialSource = credentialSource;
            this.alreadyPresent = alreadyPresent;
        }

        static Emission verbatim() {
            return new Emission(false, null, null, Set.of());
        }

        static Emission sessionNameOnly() {
            return new Emission(true, null, null, Set.of());
        }

        static Emission assumeRole(String roleArn, String credentialSource, Set<String> alreadyPresent) {
            return new Emission(true, roleArn, credentialSource, alreadyPresent);
        }

        boolean decorates() {
            return sessionName;
        }

        List<String> additions(String session) {
            if (!sessionName) {
                return List.of();
            }
            if (roleArn == null) {
                return List.of(ROLE_SESSION_NAME + " = " + session);
            }
            List<String> additions = new ArrayList<>(3);
            if (!alreadyPresent.contains(ROLE_ARN)) {
                additions.add(ROLE_ARN + " = " + roleArn);
            }
            if (!alreadyPresent.contains(CREDENTIAL_SOURCE)) {
                additions.add(CREDENTIAL_SOURCE + " = " + credentialSource);
            }
            if (!alreadyPresent.contains(ROLE_SESSION_NAME)) {
                additions.add(ROLE_SESSION_NAME + " = " + session);
            }
            return additions;
        }
    }

    /** What the decoration found and changed. Nothing here is sensitive: section names only. */
    public static final class Result {

        private final String content;
        private final List<String> sectionsFound;
        private final List<String> sectionsDecorated;
        private final List<String> sectionsAppended;
        private final boolean unprofiledAttributed;

        Result(
                String content,
                List<String> found,
                List<String> decorated,
                List<String> appended,
                boolean unprofiledAttributed) {
            this.content = content;
            this.sectionsFound = List.copyOf(found);
            this.sectionsDecorated = List.copyOf(decorated);
            this.sectionsAppended = List.copyOf(appended);
            this.unprofiledAttributed = unprofiledAttributed;
        }

        /**
         * @return whether calls naming no profile will carry the build's session name. False is a normal,
         *     safe outcome — it means those calls behave exactly as they do without the plugin.
         */
        public boolean unprofiledAttributed() {
            return unprofiledAttributed;
        }

        @NonNull
        public String content() {
            return content;
        }

        /** Every section header in the node's own configuration, in file order. */
        @NonNull
        public List<String> sectionsFound() {
            return sectionsFound;
        }

        /** Sections that gained a session name: they assume a role and had not pinned one. */
        @NonNull
        public List<String> sectionsDecorated() {
            return sectionsDecorated;
        }

        /** Sections added from Jenkins configuration because the node did not define them. */
        @NonNull
        public List<String> sectionsAppended() {
            return sectionsAppended;
        }
    }

    /** Copies one section verbatim, inserting any additions after its last non-blank line. */
    private static void emit(List<String> out, List<String> section, Emission emission, String sessionName) {
        List<String> additions = emission.additions(sessionName);
        if (additions.isEmpty()) {
            out.addAll(section);
            return;
        }
        int lastMeaningful = -1;
        for (int i = 0; i < section.size(); i++) {
            if (!section.get(i).trim().isEmpty()) {
                lastMeaningful = i;
            }
        }
        out.addAll(section.subList(0, lastMeaningful + 1));
        out.addAll(additions);
        out.addAll(section.subList(lastMeaningful + 1, section.size()));
    }

    /**
     * Checks that a generated configuration is safe to hand to a build.
     *
     * <p>The plugin's fail-open guard catches <em>exceptions</em>. It does not catch the case that
     * matters most here: producing a file successfully that is subtly wrong. If that file were exported,
     * every AWS call in the build would fail, nothing would have thrown, and the guard would never fire.
     * So the output is checked against the input before it is used, and anything unexpected means
     * contributing nothing — the same outcome as any other failure.
     *
     * <p>What is verified is exactly the property the transform claims: <b>additions only</b>. Every line
     * of the original must still be present, in order, and every section header must survive.
     *
     * @return empty when the output is safe, otherwise a human-readable reason it is not
     */
    @NonNull
    public static java.util.Optional<String> validate(@NonNull String original, @NonNull String generated) {
        // Trailing blank lines carry no meaning in an INI file, and the two sides do not agree about
        // them: joining lines with "\n" turns a final empty element into a single terminating newline,
        // so a node file ending in a blank line loses it whenever its last section is decorated.
        // Comparing without them keeps this check about content, which is what it is for. Measured on a
        // real controller configuration, where the false positive it caused suppressed decoration
        // entirely — the check failed safe, but for a difference that does not matter.
        List<String> before = withoutTrailingBlanks(split(original));
        List<String> after = withoutTrailingBlanks(split(generated));
        if (after.size() < before.size()) {
            return java.util.Optional.of("generated configuration is shorter than the node's (" + after.size()
                    + " lines vs " + before.size() + "): the transform must only add lines");
        }
        // Every original line must appear in the output, in the original order.
        int cursor = 0;
        for (String line : before) {
            boolean found = false;
            while (cursor < after.size()) {
                if (after.get(cursor++).equals(line)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return java.util.Optional.of("a line from the node's configuration is missing or reordered "
                        + "in the generated file: " + summarize(line));
            }
        }
        Set<String> beforeSections = sectionsOf(before);
        Set<String> afterSections = sectionsOf(after);
        if (!afterSections.containsAll(beforeSections)) {
            Set<String> missing = new LinkedHashSet<>(beforeSections);
            missing.removeAll(afterSections);
            return java.util.Optional.of("section(s) lost in the generated file: " + missing);
        }
        return duplicateKey(after);
    }

    /**
     * Rejects a section that declares the same key twice.
     *
     * <p>"Additions only" is necessary and not sufficient. A duplicated key <em>is</em> an addition, so
     * the structural check passes — while botocore refuses to parse the file at all, failing every
     * profile in it and therefore every AWS call in the build. That is worse than losing attribution,
     * which is the one outcome this plugin must never cause.
     *
     * <p>Found in production: a profile carrying {@code credential_source} but no {@code role_arn} was
     * given the full assume-role triple, duplicating the key it already had.
     */
    @NonNull
    private static java.util.Optional<String> duplicateKey(List<String> lines) {
        // Slice into sections and run the SAME parser the writer used. When these two disagreed about
        // what counts as a key, the guard was blind to exactly the corruption the writer had produced.
        String section = "";
        int start = 0;
        for (int i = 0; i <= lines.size(); i++) {
            boolean end = i == lines.size();
            if (!end && !SECTION.matcher(lines.get(i)).matches()) {
                continue;
            }
            java.util.Optional<String> defect = firstRepeat(optionKeysOf(lines.subList(start, i)), section);
            if (defect.isPresent()) {
                return defect;
            }
            if (!end) {
                section = SECTION.matcher(lines.get(i)).matches()
                        ? lines.get(i).trim().replaceAll("^\\[|\\]$", "")
                        : section;
                start = i;
            }
        }
        return java.util.Optional.empty();
    }

    /** The first key declared twice in one section, if any. */
    @NonNull
    private static java.util.Optional<String> firstRepeat(List<String> keys, String section) {
        Set<String> seen = new LinkedHashSet<>();
        for (String key : keys) {
            if (!seen.add(key)) {
                return java.util.Optional.of("the generated file declares '" + key + "' twice in [" + section
                        + "], which makes the whole file unparseable");
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Whether a section is one an AWS SDK resolves credentials from.
     *
     * <p>Only {@code [default]} and {@code [profile x]} are. A shared configuration file also holds
     * sections that are not profiles at all — {@code [sso-session x]}, {@code [services x]},
     * {@code [plugins]} — and writing a {@code role_arn} into one of those would corrupt it. The
     * transform must know the difference rather than treat every section alike.
     */
    private static boolean isProfileSection(@CheckForNull String section) {
        return DEFAULT_SECTION.equals(section) || (section != null && section.startsWith("profile "));
    }

    /** Drops blank lines at the end of a file. They are not content, and the two sides disagree. */
    private static List<String> withoutTrailingBlanks(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).trim().isEmpty()) {
            end--;
        }
        return lines.subList(0, end);
    }

    private static Set<String> sectionsOf(List<String> lines) {
        Set<String> sections = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher header = SECTION.matcher(line);
            if (header.matches()) {
                sections.add(header.group(1).trim());
            }
        }
        return sections;
    }

    /** Truncates a line for an error message. Configuration lines can contain long ARNs. */
    private static String summarize(String line) {
        String trimmed = line.trim();
        return trimmed.length() <= 60 ? "\"" + trimmed + "\"" : "\"" + trimmed.substring(0, 57) + "…\"";
    }

    /** Appends profiles Jenkins knows about that the node does not. Never overrides the node. */
    private static void appendOverrides(
            List<String> out,
            Set<String> present,
            List<AwsProfile> overrides,
            String sessionName,
            String credentialSource,
            List<String> appended) {

        for (AwsProfile override : overrides) {
            if (!override.isUsable() || !override.hasRole()) {
                continue;
            }
            String section = "profile " + override.getName();
            if (present.contains(section) || present.contains(override.getName())) {
                continue; // the node already defines it; the node wins
            }
            present.add(section);
            appended.add(section);
            out.add("");
            out.add(
                    "# Added by the aws-build-session-logger Jenkins plugin: not defined in this node's configuration.");
            out.add("[" + section + "]");
            out.add(ROLE_ARN + " = " + override.getRoleArn());
            out.add("credential_source = " + credentialSource);
            out.add(ROLE_SESSION_NAME + " = " + sessionName);
            if (override.getRegion() != null) {
                out.add("region = " + override.getRegion());
            }
        }
    }

    private static List<String> split(String content) {
        List<String> lines = new ArrayList<>();
        if (content.isEmpty()) {
            return lines;
        }
        // -1 keeps trailing empties, so a file ending in a newline round-trips unchanged.
        for (String line : content.replace("\r\n", "\n").split("\n", -1)) {
            lines.add(line);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
