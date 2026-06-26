## Summary

One-paragraph description of the change.

## Type of change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds functionality)
- [ ] Breaking change (fix or feature that would break existing users)
- [ ] Documentation update
- [ ] Refactor / cleanup
- [ ] Performance improvement with a benchmark

## Which module(s)?

- [ ] `jainslee-api`
- [ ] `jainslee-core`
- [ ] `jainslee-apt`
- [ ] `jainslee-spring-boot-starter`
- [ ] `adapter-quarkus`
- [ ] `adapter-jakartaee`
- [ ] `ra-connectors`
- [ ] `docs/`
- [ ] `pom.xml` / build
- [ ] `.github/` / CI

## Tests

- [ ] I added unit tests for the new behavior
- [ ] I ran `mvn -pl <modules> test` locally and it passes
- [ ] For stress-test changes, I ran
      `SbbEntityPoolStressTest` with `-DargLine="-Xmx4g"`

## Checklist

- [ ] My commit follows [Conventional Commits](CONTRIBUTING.md#commit-message-format)
- [ ] New `.java` files have the dual-license header
- [ ] Public API changes are documented in `README.md`
- [ ] `CHANGELOG.md` updated under the `[1.1.0]` (or current) section
- [ ] No `System.out` / `System.err` introduced (log4j2 only)
- [ ] No new AGPL-3.0 code in micro-jainslee modules
- [ ] I did NOT touch `container/`, `api/`, or `tools/` (production JBoss stack)
