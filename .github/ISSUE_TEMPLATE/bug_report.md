---
name: Bug report
about: Report a reproducible defect in micro-jainslee
title: '[bug] '
labels: bug
assignees: ''
---

## Describe the bug

A clear and concise description of what the bug is.

## To reproduce

Steps to reproduce the behavior:

```java
// minimal code snippet
```

Full configuration:

- **micro-jainslee version**: (e.g. 1.1.0)
- **JDK version**: (output of `java -version`)
- **Maven version**: (output of `mvn -version`)
- **Module(s) affected**: (e.g. jainslee-core, adapter-quarkus)
- **Embedding runtime**: (e.g. plain main, Spring Boot 3.3, Quarkus 3.15, Jakarta EE 9)
- **OS**: (e.g. Linux 6.5, macOS 14, Windows 11)

## Expected behavior

A clear and concise description of what you expected to happen.

## Actual behavior

What actually happened. Include the full stack trace if applicable.

## Logs

```
Paste relevant log4j2 output here (set
com.microjainslee.core level to DEBUG for verbose output).
```

## Additional context

Anything else that might help, e.g.:

- Did this work in a previous version?
- Is the failure reproducible on the [stress test](docs/run-testcase-100k-sbb.md)?
- Are you running on virtual threads (Java 21+) or platform threads?
