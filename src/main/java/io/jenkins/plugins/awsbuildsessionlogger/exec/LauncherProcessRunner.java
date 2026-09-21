package io.jenkins.plugins.awsbuildsessionlogger.exec;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ProcessRunner} backed by a Jenkins {@link Launcher}, so the process runs <em>on the agent</em>
 * that the enclosing {@code node} block selected — not on the Jenkins controller.
 *
 * <p>This is the difference between authenticating as the right identity and the wrong one. The base
 * identity an AssumeRole chains from (typically an EC2 instance role) belongs to the machine the
 * command runs on, and the credentials it produces are only useful to later steps running on that same
 * machine. Controller-side execution would use the controller's identity — broader than an agent's, and
 * in most topologies not trusted by the target role at all — and would then leave the credentials on
 * the wrong host.
 *
 * <p>Like {@link DefaultProcessRunner}, this class is completely generic: it executes whatever argument
 * list it is given and never inspects it. Environment overrides are opaque name/value pairs.
 *
 * <p>stdout and stderr are captured separately into memory. The {@link Launcher} API drains both
 * streams itself, so unlike the {@link ProcessBuilder} path there is no pipe-buffer deadlock to avoid
 * here. Output is decoded as UTF-8, matching {@link DefaultProcessRunner}.
 *
 * <p>{@code quiet(true)} is set deliberately: without it the launcher echoes the command line into the
 * build log, and this runner's whole purpose is running a command whose arguments and output are
 * sensitive. There is intentionally no timeout in this milestone.
 */
public final class LauncherProcessRunner implements ProcessRunner {

    private final Launcher launcher;

    @CheckForNull
    private final FilePath workingDirectory;

    public LauncherProcessRunner(@NonNull Launcher launcher) {
        this(launcher, null);
    }

    /**
     * @param launcher the launcher for the node the command should run on
     * @param workingDirectory the directory to run in, typically the build's workspace; may be
     *     {@code null} to let the launcher choose
     */
    public LauncherProcessRunner(@NonNull Launcher launcher, @CheckForNull FilePath workingDirectory) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.workingDirectory = workingDirectory;
    }

    @Override
    public ProcessResult run(List<String> command) {
        return run(command, Map.of());
    }

    /**
     * Executes {@code command} on the launcher's node with {@code environmentOverrides} applied on top
     * of that node's environment.
     *
     * @param command the executable and its arguments; must be non-empty
     * @param environmentOverrides variables to set in the child environment. Values must be non-null;
     *     unlike {@link DefaultProcessRunner}, the {@link Launcher} API has no way to express "remove
     *     this variable", so a null value is rejected rather than silently ignored.
     * @return the captured result (stdout, stderr, exit code)
     * @throws ProcessExecutionException if the process cannot be started or run to completion
     */
    public ProcessResult run(List<String> command, Map<String, String> environmentOverrides) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(environmentOverrides, "environmentOverrides");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        for (Map.Entry<String, String> override : environmentOverrides.entrySet()) {
            if (override.getValue() == null) {
                throw new IllegalArgumentException(
                        "environment override '" + override.getKey() + "' must not have a null value");
            }
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        Launcher.ProcStarter starter = launcher.launch()
                .cmds(command)
                .envs(environmentOverrides)
                .stdout(stdout)
                .stderr(stderr)
                .quiet(true);
        if (workingDirectory != null) {
            starter = starter.pwd(workingDirectory);
        }

        int exitCode;
        try {
            Proc process = starter.start();
            exitCode = process.join();
        } catch (IOException e) {
            throw new ProcessExecutionException("Failed to start process: " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessExecutionException("Interrupted while waiting for process: " + command, e);
        }

        return new ProcessResult(
                command, exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }
}
