package io.jenkins.plugins.awsbuildsessionlogger.exec;

import java.util.List;

/**
 * Hand-written {@link ProcessRunner} test double: records the command it was given and returns a preset
 * {@link ProcessResult} or throws a preset failure. No real processes.
 *
 * <p>Public so tests in other packages (e.g. the {@code auth.cli} adapter tests) can reuse it. The
 * {@link #failingExecution(String)} helper constructs the {@link ProcessExecutionException} here, inside
 * the execution package, so those tests can exercise the "process could not run" path without importing
 * the execution-layer exception themselves.
 */
public final class FakeProcessRunner implements ProcessRunner {

    private List<String> lastCommand;
    private ProcessResult result;
    private RuntimeException toThrow;

    public FakeProcessRunner returning(ProcessResult result) {
        this.result = result;
        this.toThrow = null;
        return this;
    }

    /** Simulate a process that ran and produced this stdout/stderr/exit code. */
    public FakeProcessRunner returning(int exitCode, String stdout, String stderr) {
        return returning(new ProcessResult(List.of("fake"), exitCode, stdout, stderr));
    }

    /** Simulate an execution-layer failure (process could not be started or completed). */
    public FakeProcessRunner failingExecution(String message) {
        this.toThrow = new ProcessExecutionException(message);
        this.result = null;
        return this;
    }

    public List<String> lastCommand() {
        return lastCommand;
    }

    @Override
    public ProcessResult run(List<String> command) {
        this.lastCommand = command;
        if (toThrow != null) {
            throw toThrow;
        }
        return result;
    }
}
