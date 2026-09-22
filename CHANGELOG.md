# Changelog

All notable changes are recorded here. Versions follow `major.minor.patch`.

## 1.0.0 — unreleased

First public release.

- Decorates each agent's own AWS configuration so that every AWS call a build makes is
  recorded in CloudTrail under `jk-<job>-<build>`. Covers Pipeline and Freestyle builds.
- Optional attribution of calls that name no profile, by re-assuming each agent's own
  instance role.
- Observe-only mode (on by default), per-job include and exclude patterns, diagnostics.
- `withAwsBuildRole` block step for assuming one specific role explicitly.
- Configuration as Code support under `unclassified.awsBuildSessionLogger`.
- Warns at load time when a profile's role ARN is malformed.
- README: optional IAM enforcement guide (trust-policy conditions and an agent-role deny), with its limits.
