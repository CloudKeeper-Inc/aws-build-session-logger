# Architecture

This document is for people changing the plugin. It covers how a build is decorated,
which properties the code must never break, and why the design is the way it is. The
README covers usage.

## The one idea

The plugin never authenticates on a build's behalf. It takes the AWS configuration the
agent already uses, adds `role_session_name = jk-<job>-<build>` where a role is assumed,
and points `AWS_CONFIG_FILE` at the copy. The AWS tool then does its own AssumeRole under
that name, and refreshes it natively.

This is why the plugin works with the AWS CLI, every AWS SDK and Terraform without any of
them knowing it exists. It is also why it needs no changes to Jenkinsfiles.

## Packages

| Package | Contents |
|---|---|
| `managed` | The main path: `ManagedAwsContext` (Pipeline), `ManagedAwsFreestyleEnvironment` (Freestyle), `AwsConfigOverlay` (the config transform), cleanup |
| `config` | `AwsBuildSessionConfiguration` (global settings, `@Symbol("awsBuildSessionLogger")`), `AwsProfile` |
| `auth` | `SessionName`, plus `AuthCore` and the STS port used by the explicit steps |
| `exec` | Process runners: `LauncherProcessRunner` (runs on the agent), `DefaultProcessRunner` |
| `steps` | `withAwsBuildRole` (block-scoped explicit step) and `awsAssumeBuildRole` (legacy) |

## Flow of a Pipeline build

`ManagedAwsContext` is a `DynamicContext.Typed<EnvironmentExpander>`. Jenkins consults it
whenever a step needs its environment, so it reaches every step: shared libraries,
`parallel` branches, `retry` attempts, and `node` blocks on other agents.

1. **Gates.** It returns `null` (contributes nothing) unless the master switch is on, the
   job matches the include/exclude patterns, and there is a workspace.
2. **Workspace anchoring.** The generated file belongs to the *build's* workspace, not
   the current directory. Inside `dir('app')` the current `FilePath` is part of the checked
   out source. `buildWorkspace()` maps it back to the workspace root, and it recognises
   concurrent `job@2` workspaces.
3. **Prepare once, under a lock.** Results are memoised per `run + workspace`. A per-key
   lock stops parallel branches from writing the same file at once. The memo is reused
   only if the file still exists; `cleanWs()` in the middle of a build is common, and an
   AWS SDK reads a missing config file as an empty one.
4. **Read the agent's config**, from `AWS_CONFIG_FILE` in the agent's environment or
   `$HOME/.aws/config`.
5. **Resolve the agent's own role** (only with unprofiled attribution on). This runs on the
   agent: an IMDSv2 lookup, then one real `aws sts assume-role` of the role into itself to
   prove AWS allows it. The result, including failure, is cached per node name.
6. **Decorate** with `AwsConfigOverlay`, then **validate** the output. Every original line
   must still be present and in order, no section may be lost, and no key may be
   duplicated.
7. **Write** `<workspace>@tmp/aws-build-session-logger/config` (directory `0700`, file
   `0600`) and record its location on the build for cleanup.
8. **Observe only?** Stop here and export nothing.
9. **Merge** with the enclosing environment as `merge(ours, existing)`, then run the
   additions-only check (see below).

Freestyle builds reach the same `prepareOnce()` through an `EnvironmentContributor`. The
decoration, validation, memo and cleanup are shared, so the two paths cannot drift.

## Invariants — do not break these

**1. Fail open.** Every contribution runs inside `guarded()`. It catches `Throwable`,
rethrows only `InterruptedException`, and contributes nothing. Losing attribution is
acceptable; failing a build is not.

**2. Additions only, checked at runtime, on both surfaces.**
- *Config file:* `AwsConfigOverlay.validate()` compares output with input.
- *Environment (Pipeline):* `wouldRemoveSomething()` expands the enclosing environment and
  the merged one, and declines if any variable would be dropped or changed.

Why a runtime check rather than code that is correct by construction: the exception guard
cannot catch a contribution that succeeds and still takes something away. A real
production build once lost its credential binding exactly that way.

**3. Never shadow the enclosing context.** `ContextVariableSet.get` scans the current
level, then every `DynamicContext`, and only then the parent level. A `DynamicContext` that
always answers therefore **hides** everything an enclosing `withCredentials` or `withEnv`
published, as soon as an inner `dir` or `ws` adds a level. Always merge with
`context.get(EnvironmentExpander.class)`, and expand ours **first** so the job's own values
win. `EnvironmentExpander.merge` null-checks only its first argument.

**4. The line-based transform only inserts lines.** Parsing the INI and writing it back
would drop comments and reorder keys, and it would corrupt sections the parser doesn't
understand (`[sso-session …]`, `[services …]`). The key parser `optionKeysOf()` follows
configparser's rules: both `=` and `:` delimiters, and indentation-based continuation
lines. The writer and the duplicate-key guard use the same parser; when they once
disagreed, the guard missed exactly the corruption the writer produced.

**5. Never change who a profile authenticates as.** A profile that uses SSO,
`source_profile`, `credential_process`, static keys or web identity is left untouched. A
profile that already sets `role_session_name` is left untouched too.

**6. No credentials in the generated file.** It holds only the agent's own config, a
session name, and possibly a role ARN and `credential_source`. Its cleanup is best effort
precisely because it contains nothing secret.

**7. One STS call, per node, only.** The self-assume probe is the only STS call on the
managed path. Do not add others: `DynamicContext` is consulted on every step.

**8. Nothing organisation-specific in the source.** No account IDs, role names, profile
names or per-AWS-service logic. Profile names are data in someone's configuration.

## Key decisions

- **Decorate, don't replace.** An earlier design generated a config from Jenkins-side
  profile mappings. It could not cover calls that name a profile the mapping didn't know
  about, or calls that name none. Decorating the agent's own config covers both, and it
  can't change what a build is allowed to do.
- **`@tmp`, not the workspace.** Workspace cleanups and `git clean -fdx` would remove the
  file. `@tmp` is also where container mounts can see it.
- **`AWS_SHARED_CREDENTIALS_FILE` is not set.** Pointing it at a plugin-owned file breaks
  profiles that chain through `source_profile` to a credentials-file profile.
- **Self-assume for unprofiled calls.** Re-assuming the agent's own role keeps the same
  principal ARN, so resource policies that grant access to that role keep working.
  Assuming a different role with the same permissions would not.
- **Observe-only ships on.** When an admin turns the master switch on, nothing changes
  until they also untick observe-only.
- **Freestyle uses `putAll`.** By the time contributors run, the Freestyle environment
  already contains the agent's OS environment. A "don't overwrite" rule would therefore
  defer to any agent that sets `AWS_CONFIG_FILE` in its service definition, and silently
  drop attribution there.
- **Session name shape `jk-<job>-<build>` is fixed.** IAM trust policies can require
  `"sts:RoleSessionName": "jk-*"`; changing the shape would break them.

## Testing

- Unit tests where possible. `AwsConfigOverlayTest`, `SessionNameTest` and the
  additions-only tests need no Jenkins.
- `JenkinsRule` (`@WithJenkins`) only where a running Jenkins is needed: extension
  registration, persistence, Pipeline step context, agents.
- `ProductionFailureModesTest` holds one test per failure shape seen in real builds:
  shadowing, stale memo, parallel race, source-tree pollution, observe-only
  invisibility. Add to it whenever a new real-world failure is found.
- `ConfigurationAsCodeTest` loads and exports real JCasC YAML.
- `io.jenkins.plugins.awsbuildsessionlogger.awsExecutable` and `.nodeConfigFile` are
  system-property test hooks. They replace the `aws` binary and the agent config path.
