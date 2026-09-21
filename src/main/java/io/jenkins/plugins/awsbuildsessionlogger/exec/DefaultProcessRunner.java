package io.jenkins.plugins.awsbuildsessionlogger.exec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@link ProcessRunner} backed by {@link ProcessBuilder}.
 *
 * <p>The child process inherits this JVM's environment, so ambient AWS configuration (e.g.
 * {@code AWS_PROFILE}, {@code AWS_DEFAULT_REGION}, or an instance-metadata role) is available to the
 * command without this class reading any AWS config files itself.
 *
 * <p>{@link #run(List, Map)} additionally applies per-invocation environment overrides on top of that
 * inherited environment. Environment — not the argument list — is the correct channel for anything
 * sensitive: a process's arguments are world-readable via {@code ps} and {@code /proc/<pid>/cmdline},
 * while its environment is not. Like the rest of this class, the override map is completely generic; it
 * carries opaque name/value pairs and this class never inspects them.
 *
 * <p>stdout and stderr are captured <em>separately</em> and drained concurrently (stderr on a helper
 * thread) so a process that writes a lot to one stream cannot deadlock by filling a pipe buffer while
 * we block reading the other. Both captures are capped, the child gets no stdin, and the wait is
 * bounded — this runner executes in the CONTROLLER JVM, so a wedged or runaway child is a
 * controller-wide problem rather than a build-local one.
 */
public final class DefaultProcessRunner implements ProcessRunner {

    /** Where the child's stdin comes from: nothing. Windows names the same sink differently. */
    private static final String NULL_DEVICE =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                    ? "NUL"
                    : "/dev/null";

    /** Upper bound on a single run. Generous for an STS call; short enough that a wedge is not forever. */
    private static final long TIMEOUT_SECONDS = 120;

    /** How long to wait for the stderr reader once the child is done or killed. */
    private static final long JOIN_MILLIS = 5_000;

    /** Cap on captured output, per stream. Orders of magnitude above any real assume-role response. */
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

    @Override
    public ProcessResult run(List<String> command) {
        return run(command, Map.of());
    }

    /**
     * Executes {@code command} with {@code environmentOverrides} applied on top of the environment
     * inherited from this JVM.
     *
     * @param command the executable and its arguments; must be non-empty
     * @param environmentOverrides variables to set in the child environment. A {@code null} value
     *     <em>removes</em> that variable from the child environment, which is how a caller can stop the
     *     child from seeing something the parent inherited.
     * @return the captured result (stdout, stderr, exit code)
     * @throws ProcessExecutionException if the process cannot be started or run to completion
     */
    public ProcessResult run(List<String> command, Map<String, String> environmentOverrides) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(environmentOverrides, "environmentOverrides");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        // Give the child no stdin. ProcessBuilder's default is a PIPE that nothing ever writes to or
        // closes, so any child that reads stdin blocks forever and the stdout read below never returns.
        // That is not merely a hang: a blocking pipe read does not respond to Thread.interrupt(), so the
        // step cannot be aborted and the thread is consumed permanently. Reachable in practice whenever
        // ambient AWS configuration makes the CLI prompt — an mfa_serial on a source profile,
        // cli_auto_prompt, or an `aws` wrapper script.
        builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(NULL_DEVICE)));
        Map<String, String> childEnvironment = builder.environment();
        for (Map.Entry<String, String> override : environmentOverrides.entrySet()) {
            if (override.getValue() == null) {
                childEnvironment.remove(override.getKey());
            } else {
                childEnvironment.put(override.getKey(), override.getValue());
            }
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessExecutionException("Failed to start process: " + command, e);
        }

        // Drain stderr on a helper thread while we read stdout on this thread. Daemon, so a stuck reader
        // can never keep the JVM alive; this runs in the controller JVM, where a leaked non-daemon thread
        // holding a file descriptor is a controller-wide problem rather than a build-local one.
        // volatile-equivalent: joinQuietly uses a timed join, which establishes no happens-before if it
        // expires, so a plain array element could be read stale on the success path.
        java.util.concurrent.atomic.AtomicReference<String> stderrHolder =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> stderrFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread stderrReader = new Thread(
                () -> {
                    try {
                        stderrHolder.set(readCapped(process.getErrorStream()));
                    } catch (Throwable t) {
                        // Throwable, not IOException: readCapped can raise OutOfMemoryError, and letting
                        // that escape left BOTH holders null, so the caller reported a confusing NPE from
                        // the result constructor while the real failure was never surfaced.
                        stderrFailure.set(t);
                    }
                },
                "aws-build-session-logger-stderr-reader");
        stderrReader.setDaemon(true);
        stderrReader.start();

        // Arm the kill BEFORE reading, not after. readCapped(stdout) is an unbounded blocking read, so a
        // child that stays alive holding stdout open without writing — `aws` retrying IMDS on a long
        // connect timeout, a wrapper blocked on a lock — never reaches waitFor at all. Ordering the
        // timeout after the read made it unreachable for exactly the wedge it was added for, and a
        // blocking pipe read does not answer Thread.interrupt(), so the thread was still lost forever.
        // Killing the process is what unblocks the read: the stream hits EOF and readCapped returns.
        java.util.concurrent.atomic.AtomicBoolean killed = new java.util.concurrent.atomic.AtomicBoolean();
        process.onExit().orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally(t -> {
            killed.set(true);
            process.destroyForcibly();
            return process;
        });

        try {
            String stdout;
            try {
                stdout = readCapped(process.getInputStream());
            } catch (Throwable t) {
                throw new ProcessExecutionException("Failed reading stdout of process: " + command, t);
            }
            int exitCode;
            try {
                // The read above returned, so the child has closed stdout — either by exiting or because
                // the reaper killed it. This wait is therefore short; the bound is belt and braces.
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new ProcessExecutionException(
                            "Process did not finish within " + TIMEOUT_SECONDS + "s: " + command);
                }
                if (killed.get()) {
                    throw new ProcessExecutionException(
                            "Process exceeded " + TIMEOUT_SECONDS + "s and was terminated: " + command);
                }
                exitCode = process.exitValue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProcessExecutionException("Interrupted while waiting for process: " + command, e);
            }

            joinQuietly(stderrReader);
            if (stderrFailure.get() != null) {
                throw new ProcessExecutionException(
                        "Failed reading stderr of process: " + command, stderrFailure.get());
            }
            return new ProcessResult(command, exitCode, stdout, stderrHolder.get() == null ? "" : stderrHolder.get());
        } catch (RuntimeException | Error e) {
            // One exit path for every failure. Previously several of them threw without stopping the
            // child or the reader, leaving a thread blocked on a stream of a process nobody would reap.
            process.destroyForcibly();
            stderrReader.interrupt();
            joinQuietly(stderrReader);
            throw e;
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads a stream, refusing to grow without bound.
     *
     * <p>{@code readAllBytes()} has no cap, and this runner executes in the <b>controller</b> JVM: a
     * runaway child that writes gigabytes takes the whole controller down rather than one build. The cap
     * is far above any legitimate {@code sts assume-role} response, so truncation means something has
     * already gone wrong.
     */
    private static String readCapped(InputStream in) throws IOException {
        try (in) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            while (out.size() < MAX_OUTPUT_BYTES && (read = in.read(buffer)) != -1) {
                out.write(buffer, 0, Math.min(read, MAX_OUTPUT_BYTES - out.size()));
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
