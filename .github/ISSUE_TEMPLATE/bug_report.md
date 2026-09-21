---
name: Bug report
about: Something the plugin did that it should not have, or did not do
labels: bug
---

**What happened, and what you expected**

**Versions:** plugin, Jenkins, Java, AWS CLI / SDK. Agent OS, and whether agents run on EC2, ECS or elsewhere.

**Job type:** Pipeline or Freestyle. If Pipeline, the smallest Jenkinsfile that shows the problem.

**Console output** with *Diagnostics* ticked (the `[aws-build-session-logger]` lines). It contains no
credentials, but **replace account IDs and internal names** before posting.

**Agent AWS config shape**, with values replaced: section names and which keys each one has.
