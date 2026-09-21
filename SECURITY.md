# Security policy

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Report them privately through GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository (*Security → Report a vulnerability*). Include what you found, how to
reproduce it, and the impact you expect. We aim to acknowledge reports within five working
days.

If this plugin is hosted by the Jenkins project, report through the
[Jenkins security process](https://www.jenkins.io/security/reporting/) instead.

## Scope

This plugin is an **attribution** tool. It fails open, and it runs inside the same trust
boundary as any Jenkinsfile, so "a pipeline author can avoid being attributed" is expected
behaviour, not a vulnerability. See *What this plugin is, and is not* in the README.

Things we do want to hear about:

- credentials exposed in logs, files or the Pipeline program state
- the plugin changing which identity a build authenticates as
- a way to make the plugin fail builds it should leave alone
- anything that lets a non-administrator change the plugin's global configuration
