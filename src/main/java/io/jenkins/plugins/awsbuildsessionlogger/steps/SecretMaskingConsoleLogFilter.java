package io.jenkins.plugins.awsbuildsessionlogger.steps;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.console.ConsoleLogFilter;
import hudson.console.LineTransformationOutputStream;
import hudson.model.Run;
import hudson.util.Secret;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces credential material with {@code ****} in the console output of a block.
 *
 * <p>Necessary because the credentials are in the block's environment, and a command inside the block
 * can print its environment — deliberately (a debugging {@code env}) or accidentally (a tool echoing
 * its configuration, a stack trace, {@code set -x}). Marking the variables sensitive stops Jenkins
 * displaying them in its own environment UI but does nothing about arbitrary process output, so this
 * filter is installed alongside.
 *
 * <p>Values are held as {@link Secret} for the same reason as in
 * {@link CredentialsEnvironmentExpander}: this object is serialized into CPS program state.
 *
 * <p>Masking is exact-substring, applied per line. It cannot catch a secret that a command transforms
 * before printing (base64, a slice, a hash). Console masking is a safety net, not a guarantee — the
 * real protection is that the credentials expire when the block ends.
 */
final class SecretMaskingConsoleLogFilter extends ConsoleLogFilter implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final String MASK = "****";

    private final List<Secret> secrets;

    /**
     * @param values the strings to mask. Null and blank values are dropped: masking the empty string
     *     would insert a mask between every character of every line.
     */
    SecretMaskingConsoleLogFilter(@NonNull List<String> values) {
        List<Secret> collected = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                collected.add(Secret.fromString(value));
            }
        }
        this.secrets = collected;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public OutputStream decorateLogger(@CheckForNull Run build, OutputStream logger) {
        if (secrets.isEmpty()) {
            return logger;
        }
        Charset charset = build == null ? StandardCharsets.UTF_8 : build.getCharset();
        List<String> plain = new ArrayList<>(secrets.size());
        for (Secret secret : secrets) {
            plain.add(secret.getPlainText());
        }
        return new MaskingOutputStream(logger, plain, charset);
    }

    private static final class MaskingOutputStream extends LineTransformationOutputStream.Delegating {

        private final List<String> secrets;
        private final Charset charset;

        MaskingOutputStream(OutputStream out, List<String> secrets, Charset charset) {
            super(out);
            this.secrets = secrets;
            this.charset = charset;
        }

        @Override
        protected void eol(byte[] b, int len) throws IOException {
            // Decode only to look for secrets; write the ORIGINAL bytes back when none matched.
            // Round-tripping every line through the build charset replaced any byte invalid in that
            // charset with U+FFFD — permanently corrupting output from a tool emitting Latin-1 or
            // Shift-JIS, or a file accidentally cat'd, and only inside the authenticated block.
            String line = new String(b, 0, len, charset);
            String masked = line;
            for (String secret : secrets) {
                masked = masked.replace(secret, MASK);
            }
            if (masked.equals(line)) {
                out.write(b, 0, len);
            } else {
                out.write(masked.getBytes(charset));
            }
        }
    }
}
