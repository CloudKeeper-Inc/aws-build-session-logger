package io.jenkins.plugins.awsbuildsessionlogger.exec;

import java.util.List;

/**
 * Runs an arbitrary command and returns its stdout, stderr, and exit code.
 *
 * <p>Intentionally generic and reusable: it executes whatever argument list it is given and knows
 * nothing about AWS, Jenkins, or any particular command. It must never become aware of what it is
 * running — that keeps it usable as the single execution primitive for both AWS-CLI authentication and
 * the future generic AWS-CLI executor, with no per-command branching.
 */
@FunctionalInterface
public interface ProcessRunner {

    /**
     * Executes {@code command} and returns its result.
     *
     * @param command the executable and its arguments; must be non-empty
     * @return the captured result (stdout, stderr, exit code)
     * @throws ProcessExecutionException if the process cannot be started or run to completion (as
     *     opposed to running and returning a non-zero exit code, which is reported in the result)
     */
    ProcessResult run(List<String> command);
}
