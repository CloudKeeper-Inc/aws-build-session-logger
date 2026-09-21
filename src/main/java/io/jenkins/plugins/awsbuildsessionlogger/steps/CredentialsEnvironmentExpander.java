package io.jenkins.plugins.awsbuildsessionlogger.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.util.Secret;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;

/**
 * Publishes credentials into the environment of a block, and nowhere else.
 *
 * <p>Secret values are held as {@link Secret}, not as {@link String}. This object is serialized into
 * the Pipeline's CPS program state, which is written to {@code program.dat} on disk and survives a
 * controller restart; {@link Secret} encrypts on serialization, so the credential material is not
 * sitting in plaintext in a file for the life of the build. It is decrypted only when the environment
 * is actually expanded for a step inside the block.
 *
 * <p>Non-secret values (region, the session name) are kept separate and stored plainly — encrypting
 * them would obscure, in the serialized form, the fact that they are <em>not</em> sensitive.
 *
 * <p>{@link #getSensitiveVariables()} tells Jenkins which variables to hide in the build's environment
 * display. It does <em>not</em> mask console output — that is
 * {@link SecretMaskingConsoleLogFilter}'s job, and both are installed together.
 */
final class CredentialsEnvironmentExpander extends EnvironmentExpander {

    private static final long serialVersionUID = 1L;

    private final Map<String, Secret> secretValues;
    private final Map<String, String> plainValues;

    CredentialsEnvironmentExpander(
            @NonNull Map<String, String> secretValues, @NonNull Map<String, String> plainValues) {
        Map<String, Secret> encrypted = new LinkedHashMap<>();
        secretValues.forEach((name, value) -> encrypted.put(name, Secret.fromString(value)));
        this.secretValues = encrypted;
        this.plainValues = new LinkedHashMap<>(plainValues);
    }

    @Override
    public void expand(@NonNull EnvVars env) {
        secretValues.forEach((name, secret) -> env.put(name, secret.getPlainText()));
        env.putAll(plainValues);
    }

    @NonNull
    @Override
    public Set<String> getSensitiveVariables() {
        return Set.copyOf(secretValues.keySet());
    }
}
