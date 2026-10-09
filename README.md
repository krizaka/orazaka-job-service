<!-- krizaka-header -->
<div align="center">

<img src=".github/assets/orazaka-logo.svg" alt="Orazaka" width="420">

# Orazaka Job Service

**The AI that never leaves home.**

Asynchronous job executor of the Orazaka engine: drains the interactive and batch lanes, owns the capability registry and the worker registry.

[![CI](https://github.com/krizaka/orazaka-job-service/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/orazaka-job-service/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Orazaka](https://img.shields.io/badge/part%20of-Orazaka-f59e0b)](https://github.com/krizaka/orazaka#repositories)
[![Docs](https://img.shields.io/badge/docs-krizaka.com-6366f1)](https://www.krizaka.com/en/products/orazaka)

[Documentation](https://www.krizaka.com/en/products/orazaka) · [Website](https://www.krizaka.com) · [Krizaka on GitHub](https://github.com/krizaka)

</div>
<!-- /krizaka-header -->

**Layer:** Orazaka AI engine · **Version:** `1.0.0-SNAPSHOT` · **License:** Apache-2.0 ·
part of the [Orazaka platform](https://github.com/krizaka/orazaka) by [Krizaka](https://krizaka.com)

## What it provides

The asynchronous executor (port `8090`): drains `orazaka.jobs.interactive` and `orazaka.jobs.batch`,
owns the capability registry (`/internal/v1/capabilities`) and worker registry
(`/internal/v1/workers`). Owns `infra/initdb/30-jobs-config.sql` (jobs, outbox, dedup, config plane,
capability seed).

## Position in the platform

| | |
|:---|:---|
| Depends on | [`orazaka-build`](https://github.com/krizaka/orazaka-build) · [`orazaka-contracts`](https://github.com/krizaka/orazaka-contracts) · [`krizaka-users`](https://github.com/krizaka/krizaka-users) · [`krizaka-billing`](https://github.com/krizaka/krizaka-billing) · [`orazaka-ai-engine`](https://github.com/krizaka/orazaka-ai-engine) |
| Used by | _no other Orazaka repository._ |
| Workspace path | `orazaka-apps/services/orazaka-job-service` |

## Build

**Inside the Orazaka workspace** (recommended — every dependency is built from source):

```bash
git clone https://github.com/krizaka/orazaka.git && cd orazaka
node scripts/workspace.mjs clone          # clones every repository at its workspace path
./mvnw -f orazaka-apps/services/orazaka-job-service/pom.xml verify
```

**Standalone** — upstream artifacts must be in `~/.m2` (built by the workspace) or resolvable from
GitHub Packages (`https://maven.pkg.github.com/krizaka/<repository>`, see the
[workspace README](https://github.com/krizaka/orazaka#consuming-packages)):

```bash
./mvnw verify
```

Requirements: JDK 21, Docker (Testcontainers integration tests).

## Governance

This repository follows the Orazaka governance contract — [AGENTS.md](https://github.com/krizaka/orazaka/blob/main/AGENTS.md)
in the workspace is normative; the local [AGENTS.md](AGENTS.md) only scopes it to this repository.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
