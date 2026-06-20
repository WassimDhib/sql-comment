# SQL Commenter Java Agent

A Java instrumentation agent that automatically injects structured SQL comments into JDBC queries, following the [SQLCommenter specification](https://google.github.io/sqlcommenter/).

SQL comments carry context (trace ID, calling service, framework, environment…) from your application all the way into your database logs and monitoring tools — without any code change.

```sql
SELECT * FROM orders WHERE id = ?
  /*traceparent='00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
    service='orders',caller='OrderService.findById',framework='spring',env='production'*/
```

## Features

- **Zero code change** — attach dynamically to a running JVM or add `-javaagent` at startup
- **JBoss EAP 8 / WildFly** — full support without server restart or configuration change
- **9 built-in rules** — traceparent, OpenTelemetry, framework, DB driver, caller, thread, service, application, environment
- **`service` rule** — extracts the top-level business service name from deep call stacks (Spring AOP, EJB, etc.)
- **Java 8–21** compatible (class file version 52)
- **ASM 9.7** embedded — no runtime dependencies

## Download

**[sql-commenter-agent-1.1.0.jar](https://github.com/WassimDhib/sql-comment/raw/main/releases/sql-commenter-agent-1.1.0.jar)** (413 Ko)

## Quick Start

### Dynamic attach (no restart)

```bash
# List running JVMs
java -jar sql-commenter-agent-1.1.0.jar --list

# Attach to a running JVM
java -jar sql-commenter-agent-1.1.0.jar \
     --pid <PID> \
     --args "rules=traceparent,framework,service,environment,static_tags=env=production"
```

### JVM startup (`-javaagent`)

```bash
java -javaagent:/path/to/sql-commenter-agent-1.1.0.jar=rules=traceparent,framework,service \
     -jar myapp.jar
```

### With a config file

```bash
java -jar sql-commenter-agent-1.1.0.jar \
     --pid <PID> \
     --args "config=/path/to/sqlcommenter.properties"
```

## Configuration

`sqlcommenter.properties`:

```properties
# Active rules (comma-separated)
# Available: traceparent, opentelemetry, framework, db_driver, caller,
#            thread, application, environment, service
rules=traceparent,framework,caller,service,environment

# Output format: url_encoded (SQLCommenter spec) or plain (human readable)
format=url_encoded

# Static tags always added to every comment
static_tags=env=production,region=us-east-1

# Caller rule: restrict to your app package
rule.caller.app_package=com.example.myapp

# Service rule: extract top-level service name from the call stack
rule.service.app_package=com.example.myapp
rule.service.prefix=com.example.myapp.services
rule.service.extract=subpackage          # subpackage | classname | class_method
# rule.service.exclude_packages=com.example.myapp.persistence
# rule.service.max_depth=200

# Debug: log each instrumented SQL query
verbose=false
```

## Built-in Rules

| Rule | Tag | Description |
|------|-----|-------------|
| `traceparent` | `traceparent` | W3C Trace Context propagation header |
| `opentelemetry` | `traceparent` | OpenTelemetry span context |
| `framework` | `framework` | Detected framework (spring, hibernate, jpa, quarkus…) |
| `db_driver` | `db_driver` | JDBC driver name (oracle, postgresql, mysql…) |
| `caller` | `caller` | Class and method that triggered the SQL call |
| `service` | `service` | Top-level business service (outermost app frame) |
| `thread` | `thread` | Thread name |
| `application` | `application` | Application name (from system property or manifest) |
| `environment` | `environment` | Deployment environment |

## JBoss EAP 8 / WildFly

Dynamic attach is fully supported on JBoss without any server configuration change.
The agent automatically patches `Module.systemPackages` at runtime to make
`SqlCommenterRuntime` visible from all JBoss modules (IronJacamar, Oracle JDBC, etc.) —
the same technique used by Byteman, Elastic APM, and Datadog.

```bash
java -jar sql-commenter-agent-1.1.0.jar \
     --pid <JBOSS_PID> \
     --args "config=/path/to/sqlcommenter.properties"
```

Expected log output:
```
INFO [JBossModules] Package io.sqlcommenter.agent.transformer added to system packages
INFO [AgentDelegate] Retransformation terminée. succeeded=254 failed=0
INFO [SqlCommenterRuntime] First SQL instrumented OK. SQL preview: SELECT.../*...*/
```

See [JBOSS_INSTALL.md](JBOSS_INSTALL.md) for the full guide including the optional
JBoss module artifact for oracle.jdbc direct interception.

## JDBC Targets

The agent instruments connection wrappers and pools by default:

| Prefix | Description |
|--------|-------------|
| `org.jboss.jca.adapters.jdbc.*` | IronJacamar / JBoss connection wrappers |
| `org.jboss.ironjacamar.*` | IronJacamar standalone |
| `org.jboss.as.connector.*` | WildFly datasource subsystem |
| `com.zaxxer.hikari.*` | HikariCP |
| `org.apache.tomcat.jdbc.*` | Tomcat JDBC pool |
| `org.apache.commons.dbcp2.*` | Apache DBCP2 |
| `com.mchange.v2.c3p0.*` | c3p0 |

Native driver classes (`oracle.jdbc.*`, `org.postgresql.*`, etc.) are excluded by default
in modular environments (JBoss Modules isolation). Override via `jdbc_prefixes=` if needed.

## How It Works

```
Your JDBC call (e.g. prepareStatement(sql))
  │
  ▼
[Instrumented bytecode — injected by ASM at attach time]
  sql = SqlCommenterRuntime.instrument(sql)
  │
  ▼
[RuleEngine — runs all active rules]
  traceparent='00-4bf92...' caller='OrderService.findById' service='orders' ...
  │
  ▼
  sql + " /*traceparent='...',caller='...',service='...'*/"
  │
  ▼
Original JDBC driver → Database
```

### Architecture (JBoss dynamic attach)

```
Bootstrap classloader
  ├── SqlCommenterRuntime      ← called from injected bytecode
  ├── SqlCommenterRuntime.Instrumenter
  └── JBossModulesIntegration  ← patches Module.systemPackages at attach time

URLClassLoader (parent=bootstrap, isolated)
  ├── AgentDelegate            ← implements Instrumenter, initialises rules
  ├── RuleEngine, AgentConfig
  ├── all rule classes
  └── ASM 9.7
```

## Building from Source

Requirements: JDK 21, ASM 9.7 (`apt-get install libasm-java` on Debian/Ubuntu).

```bash
# Compile
JAVA=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
ASM=/usr/share/java/asm-9.7.jar:/usr/share/java/asm-commons-9.7.jar:/usr/share/java/asm-util-9.7.jar:/usr/share/java/asm-tree-9.7.jar
find src/main -name "*.java" > sources.txt
$JAVA com.sun.tools.javac.Main -source 8 -target 8 -cp $ASM -d target/classes @sources.txt
```

Tests require H2 and JUnit 5 (`/usr/share/java/h2-2.2.220.jar`, `junit-platform-console-standalone-1.9.1.jar`).

## License

[Apache License 2.0](LICENSE)
