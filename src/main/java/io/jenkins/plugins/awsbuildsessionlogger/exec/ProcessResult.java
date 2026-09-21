package io.jenkins.plugins.awsbuildsessionlogger.exec;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of running a process: the command, its exit code, and its fully-captured stdout and
 * stderr. Immutable and completely generic — it carries no knowledge of what the command was for.
 *
 * <p>{@link #toString()} deliberately does not include the stdout/stderr <em>content</em> (only their
 * sizes), since for some commands that content is sensitive (e.g. STS credentials). Callers extract
 * what they need via {@link #stdout()} / {@link #stderr()}.
 */
public final class ProcessResult {

    private final List<String> command;
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    public ProcessResult(List<String> command, int exitCode, String stdout, String stderr) {
        this.command = List.copyOf(Objects.requireNonNull(command, "command"));
        this.exitCode = exitCode;
        this.stdout = Objects.requireNonNull(stdout, "stdout");
        this.stderr = Objects.requireNonNull(stderr, "stderr");
    }

    /** The command that was executed (argument list), as an unmodifiable copy. */
    public List<String> command() {
        return List.copyOf(command);
    }

    public int exitCode() {
        return exitCode;
    }

    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }

    /** @return {@code true} if the process exited with code 0. */
    public boolean succeeded() {
        return exitCode == 0;
    }

    @Override
    public String toString() {
        return "ProcessResult{command=" + command + ", exitCode=" + exitCode + ", stdoutChars=" + stdout.length()
                + ", stderrChars=" + stderr.length() + "}";
    }
}
