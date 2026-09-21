package io.jenkins.plugins.awsbuildsessionlogger.exec;

/**
 * Signals that a process could not be run to completion — it failed to start, or reading its output or
 * waiting for it was interrupted. This is distinct from a process that <em>ran</em> and returned a
 * non-zero exit code (that is reported via {@link ProcessResult#exitCode()}, not an exception).
 *
 * <p>This type belongs to the execution layer only. Callers such as {@code CliStsAssumeRole} catch it
 * and translate it into their own domain exception, so the rest of the project never sees it and stays
 * agnostic to how work is executed.
 */
public class ProcessExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProcessExecutionException(String message) {
        super(message);
    }

    public ProcessExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
