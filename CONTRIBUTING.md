# Contributing

Thanks for helping. Bug reports, fixes, tests and documentation are all welcome.

## Before you start

- **Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)**, especially *Invariants*. Most
  review comments on this plugin come back to one of them.
- For anything larger than a small fix, open an issue first so the approach can be agreed
  before you write the code.
- Report security issues privately, as described in [`SECURITY.md`](SECURITY.md), not in a
  public issue.

## Building and testing

You need JDK 21 and Maven 3.9+ (Jenkins 2.555.x requires Java 21).

```sh
mvn verify                      # build, format check, all tests
mvn spotless:apply              # fix formatting (the build fails on unformatted code)
mvn hpi:run                     # start a local Jenkins with the plugin at http://localhost:8080/jenkins
mvn -Dtest=AwsConfigOverlayTest test   # run one test class
```

`mvn verify` produces a `-SNAPSHOT` build. Release builds use `mvn -Dchangelist= clean verify`.

Tests run `sh` steps, so they need Linux or macOS.

## Rules for changes

These come from real failures, so please keep them:

1. **Fail open.** Nothing the plugin does may fail a build. New code on the managed path
   goes inside the existing guard; losing attribution is acceptable, a failed build is not.
2. **Only add.** Never remove, reorder or change a line of the agent's AWS config, or a
   variable the build already set. Both are checked at runtime; don't weaken the checks.
3. **Merge, never shadow.** A `DynamicContext` must merge with the enclosing
   `EnvironmentExpander`, not replace it. See invariant 3 in the architecture doc.
4. **No credentials** in the generated file, in return values, or in logs.
5. **No organisation-specific or AWS-service-specific logic.** No account IDs, role or
   profile names, and never `if (service == "ecs")`.
6. **Don't change the `jk-<job>-<build>` shape** without an issue first; IAM policies
   depend on it.
7. **Every fix for a real-world failure comes with a test** reproducing it, usually in
   `ProductionFailureModesTest`.

## Tests

- Prefer plain unit tests. Use `@WithJenkins` / `JenkinsRule` only when the test needs a
  running Jenkins: extension registration, persistence, Pipeline steps, agents.
- Use placeholder account IDs (`111111111111`, `222222222222`, `123456789012`), never real
  ones.
- Don't call real AWS. Use the `io.jenkins.plugins.awsbuildsessionlogger.awsExecutable`
  and `.nodeConfigFile` system properties to substitute a fake `aws` binary and a fake
  agent config.

## Pull requests

- Keep each PR to one change, with a description of *why*.
- Update `README.md` when user-visible behaviour changes, `docs/ARCHITECTURE.md` when an
  invariant or the flow changes, and `CHANGELOG.md` in every case.
- CI must be green.

By contributing, you agree that your contributions are licensed under the
[MIT License](LICENSE).
