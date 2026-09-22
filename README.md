# AWS Build Session Logger

A Jenkins plugin that makes every AWS call a build makes show up in CloudTrail under a
session name that identifies the build:

```
jk-<job>-<build>          e.g.  jk-team-a-deploy-api-142
```

Without it, a build's AWS calls carry names like `i-0abc…`, `botocore-session-1712345678`
or `aws-go-sdk-…`. They say *which machine* made a call, not *which build*.

The plugin needs **no changes to Jenkinsfiles, shared libraries or Terraform code**. It
doesn't run AWS commands and it doesn't hand out credentials. It decorates the AWS
configuration the agent already has, so the AWS CLI, boto3, Terraform and every other
AWS SDK pick the name up by themselves.

## How it works

For every Pipeline and Freestyle build in scope, on each agent the build uses:

1. The plugin reads the agent's own AWS config: `$AWS_CONFIG_FILE` if the agent sets it,
   otherwise `$HOME/.aws/config`.
2. It writes a copy to `<workspace>@tmp/aws-build-session-logger/config`, with
   `role_session_name = jk-<job>-<build>` added to every profile that assumes a role.
   It only **adds** lines. Nothing in the original is changed, reordered or removed, and
   the result is checked for that before it is used.
3. It points the build at the copy by exporting `AWS_CONFIG_FILE`.
4. When the build finishes, it deletes the copy.

Your role ARNs, base identities, chained profiles and regions stay exactly as the agent
defines them. The AWS tool still performs its own AssumeRole (and refreshes it) under the
new name.

**Calls that name no profile** (a bare `aws s3 ls`) use the instance's own credentials,
whose session name EC2 fixes to the instance ID. Turn on *Attribute unprofiled calls as the
node's own instance role* to cover these. The plugin then asks each agent for its instance
role over IMDSv2, checks with one real `sts:AssumeRole` that the role may assume itself,
and adds a `[default]` profile that re-assumes that same role under the build's name. The
principal stays the same, so permissions and resource policies are unaffected. If an agent
has no instance role, or its role cannot assume itself, it is left exactly as it was.

For this to work, AWS must allow the role to assume itself. Typically that means the
role's trust policy names its own ARN (AWS requires self-assumption to be allowed
explicitly). The plugin checks with a real call and skips any agent where it fails.
A trust-policy statement like this one grants it:

```json
{
  "Effect": "Allow",
  "Principal": { "AWS": "arn:aws:iam::123456789012:role/jenkins-agent" },
  "Action": "sts:AssumeRole"
}
```

### What a build sees

| Variable | Value |
|---|---|
| `AWS_CONFIG_FILE` | Path to the decorated copy |
| `AWS_BUILD_SESSION_NAME` | `jk-<job>-<build>`, for scripts that want to log or tag with it |
| `AWS_ROLE_SESSION_NAME` | The same name, for tools that assume a role themselves and read this variable |

`AWS_SHARED_CREDENTIALS_FILE` is never touched. No credentials are exported, and none are
written to disk.

## Requirements

- Jenkins 2.555.3 or newer, which itself requires Java 21.
- **Linux or macOS agents.** On Windows agents the plugin contributes nothing (it cannot
  set file permissions), and builds run exactly as they would without it.
- The `aws` CLI on agents, but only if you use unprofiled attribution or the
  `withAwsBuildRole` step.

## Getting started

1. Install the plugin. Nothing changes until you turn it on.
2. Go to *Manage Jenkins → System → AWS Build Session Logger*.
3. Tick **Managed authentication**. **Observe only** is ticked by default. In that mode
   every in-scope build prepares the file and says in its console log what it *would* have
   done, but exports nothing.
4. Let some real builds run and read their console output (look for
   `[aws-build-session-logger]`).
5. Untick **Observe only** to start exporting.

### Settings

| Setting | Default | What it does |
|---|---|---|
| Managed authentication | off | Master switch. Turning it off takes effect for new builds immediately, with no restart |
| Apply to jobs matching | blank (all jobs) | Regex over the job's full name, e.g. `team-a/.*`. An invalid regex matches nothing |
| Except jobs matching | blank | Regex; wins over the include pattern. Use it to take one job out of scope quickly. **An invalid regex excludes nothing**, so check the job's console afterwards |
| Attribute unprofiled calls as the node's own instance role | off | See *How it works* |
| Agent base identity | `Ec2InstanceMetadata` | `credential_source` written into profiles the plugin adds. `EcsContainer` or `Environment` for agents that don't run on EC2 |
| Observe only | **on** | Prepare and report, export nothing |
| Diagnostics | off | Prints what was found and changed. No file contents, no credentials |

### Configuration as Code

```yaml
unclassified:
  awsBuildSessionLogger:
    managedAuthentication: true
    observeOnly: false
    jobNamePattern: "team-a/.*"
    attributeUnprofiledAsNodeRole: true
    profiles:                          # optional, see below
      - name: "non_prod"
        mode: "AssumeRole"
        roleArn: "arn:aws:iam::123456789012:role/non_prod"
        region: "us-east-1"
```

**Profiles** are optional and can only be set through Configuration as Code or XML. They
have two uses:

- An `AssumeRole` profile is added to the decorated config **only if the agent's own
  config doesn't already define it**. The agent's own config always wins.
- They are the source for the `withAwsBuildRole` step.

## Explicit step: `withAwsBuildRole`

If you would rather have a block that assumes one specific role, the plugin also offers a
step:

```groovy
node {
    withAwsBuildRole('non_prod') {           // or: withAwsBuildRole(roleArn: 'arn:aws:iam::…:role/x')
        sh 'aws sts get-caller-identity'
    }
}
```

It runs `aws sts assume-role` on the agent under `jk-<job>-<build>`. It exports
`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN` (masked in the console)
plus the region, for the duration of the block. The credentials are not refreshed, so the
one-hour limit on chained role sessions applies inside the block.

## What this plugin is, and is not

**It is an attribution tool, not an enforcement control.** Read this before relying on it
for security.

- **It fails open by design.** Any error means the plugin contributes nothing and the build
  runs exactly as it would without it. Losing attribution is acceptable; failing a
  deployment is not.
- **Anyone who can edit a Jenkinsfile can bypass it.** They can export their own AWS
  credentials, point `AWS_CONFIG_FILE` elsewhere, or call `sts assume-role` with any
  session name they like, including a fake `jk-` name.
- **For enforcement, use IAM.** A trust-policy condition on your target roles
  (`"StringLike": {"sts:RoleSessionName": "jk-*"}`) makes AWS refuse sessions without the
  build prefix. CloudTrail alerts on session names that don't start with `jk-` catch
  whatever the plugin missed.

### Known limitations

- **A tool's own second AssumeRole.** A Terraform provider with its own `assume_role` block
  names that session itself (`aws-go-sdk-…`) and ignores `AWS_ROLE_SESSION_NAME`. Those
  calls are still traceable: the AssumeRole event that created them has the build's `jk-`
  session as its caller. To label them directly, set `session_name` in the provider block
  from `AWS_BUILD_SESSION_NAME`.
- **Credentials in environment variables win.** If a build exports its own
  `AWS_ACCESS_KEY_ID`, every AWS SDK uses those and ignores `AWS_CONFIG_FILE`.
- **Freestyle jobs:** the plugin overwrites the three variables above if the agent's own
  environment sets them. Pipeline jobs keep any value an enclosing block (`withEnv`,
  `withCredentials`, …) set.
- **Similar job names can collide.** Job names are cleaned to the characters STS allows, so
  `a/b` and `a-b` both become `jk-a-b-<build>`. Names too long for STS's 64-character limit
  are shortened and get a short hash so they stay unique.
- **Agents identified by name.** An agent's instance role is looked up once and remembered
  by node name until Jenkins restarts. If you re-provision a permanent agent under the same
  name with a different instance role, restart Jenkins.
- **Container agents** (`container()`, `docker.inside`) are untested.

## Finding calls that weren't attributed

- **Jenkins side:** add a log recorder on `io.jenkins.plugins.awsbuildsessionlogger` at
  WARNING (*Manage Jenkins → System Log*). It shows every time the plugin declined to
  contribute, and why. It only sees what the plugin knows about.
- **AWS side (the authoritative check):** in CloudTrail, group calls made by your Jenkins
  roles by session name. `jk-…` is attributed. `i-…` is an unprofiled call that wasn't
  covered. `aws-go-sdk-…` and `botocore-session-…` are tools that assumed a role
  themselves.

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): how it works inside, the invariants it
  keeps, and why.
- [`CONTRIBUTING.md`](CONTRIBUTING.md): building, testing, and the rules for changes.

## License

[MIT](LICENSE) © 2026 CloudKeeper
