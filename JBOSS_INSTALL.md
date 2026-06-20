# Installation on JBoss EAP 8 / WildFly — Definitive Guide

## Confirmed diagnosis from logs

The agent works correctly: `succeeded=254 failed=0`,
`WrappedConnection.prepareStatement(String)` is instrumented (10 methods).

The only missing piece was making `SqlCommenterRuntime` visible from JBoss
system modules (IronJacamar, Oracle JDBC). This is now solved automatically
by patching `Module.systemPackages` at runtime during attach.

## Dynamic attach (no server restart, no config change)

```bash
# List running JVMs
java -jar sql-commenter-agent-1.1.0.jar --list

# Attach to WildFly/JBoss
java -jar sql-commenter-agent-1.1.0.jar \
     --pid <JBOSS_PID> \
     --args "config=/path/to/sqlcommenter.properties"
```

Expected in `server.log` after attach:

```
INFO  [SqlCommenterAgent] Initialisation (dynamic=true)
INFO  [JBossModules] JBoss Modules detected — injecting system package...
INFO  [JBossModules] Package io.sqlcommenter.agent.transformer added to system packages
INFO  [RuleEngine] Active rules: [...] | staticTags={...}
INFO  [AgentDelegate] Retransformation terminée. succeeded=254 failed=0
INFO  [SqlCommenterRuntime] First SQL instrumented OK. SQL preview: SELECT.../*...*/
```

## Configuration

`sqlcommenter.properties`:

```properties
# Active rules
rules=traceparent,framework,caller,service,environment

# Output format (url_encoded = SQLCommenter spec, plain = human readable)
format=url_encoded

# Static tags always added to every SQL comment
static_tags=env=production

# Caller rule: restrict to your application package
rule.caller.app_package=com.example.myapp

# Service rule: extract the top-level service name from the call stack
rule.service.app_package=com.example.myapp
rule.service.prefix=com.example.myapp.services
rule.service.extract=subpackage
# Optionally exclude internal infra layers
# rule.service.exclude_packages=com.example.myapp.persistence,com.example.myapp.framework

# Verbose: log each instrumented SQL (for debugging only)
verbose=false
```

## Why global-modules don't work for system modules

`<global-modules>` in `standalone.xml` only applies to **application deployments**,
not to system modules like `org.jboss.ironjacamar.jdbcadapters`.

The agent solves this by patching `Module.systemPackages` and `Module.systemPaths`
directly via reflection at attach time — the same technique used by Byteman, Elastic APM,
and other production agents.

## Startup mode (-javaagent)

Alternatively, add to `standalone.conf`:

```bash
JAVA_OPTS="$JAVA_OPTS -javaagent:/path/to/sql-commenter-agent-1.1.0.jar=config=/path/to/sqlcommenter.properties"
```

No `jboss.modules.system.pkgs` property needed — the agent handles this automatically.
