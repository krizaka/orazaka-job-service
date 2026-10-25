<!-- krizaka-header -->
<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/orazaka-dark.svg">
  <img src="https://raw.githubusercontent.com/krizaka/.github/main/profile/assets/orazaka-light.svg" alt="Orazaka" width="96">
</picture>

# Orazaka Job Service

**The AI that never leaves home.**

Asynchronous job executor of the Orazaka engine: drains the interactive and batch lanes, owns the capability registry and the worker registry.

[![CI](https://github.com/krizaka/orazaka-job-service/actions/workflows/ci.yml/badge.svg)](https://github.com/krizaka/orazaka-job-service/actions/workflows/ci.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/krizaka/orazaka-job-service/badge)](https://scorecard.dev/viewer/?uri=github.com/krizaka/orazaka-job-service)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Orazaka](https://img.shields.io/badge/part%20of-Orazaka-f67e23)](https://github.com/krizaka/orazaka#repositories)
[![Docs](https://img.shields.io/badge/docs-krizaka.com-f67e23)](https://www.krizaka.com/en/products/orazaka)

[Documentation](https://www.krizaka.com/en/products/orazaka) · [Website](https://www.krizaka.com) · [Krizaka on GitHub](https://github.com/krizaka)

</div>
<!-- /krizaka-header -->

**Layer:** Orazaka AI engine · **Version:** `1.0.0-SNAPSHOT` · **License:** Apache-2.0 ·
part of the [Orazaka platform](https://github.com/krizaka/orazaka) by [Krizaka](https://krizaka.com)

## What it provides

The asynchronous executor (port `8090`): drains `orazaka.jobs.interactive` and `orazaka.jobs.batch`,
owns the capability registry (`/internal/v1/capabilities`) and worker registry
(`/internal/v1/workers`). Owns `infra/initdb/30-jobs-config.sql` (jobs, outbox, dedup, config plane,
capability seed, and the grounded-research tables of ADR-087 — sources, the document cache keyed by source,
URL and content hash, the fetch audit, consents — with research off and every source disabled by default; and the law corpus of ADR-089 — instruments, dated and
hashed article versions, imports, the retrieval cache, rule proposals, the demand for unsupported kinds — with the
`law.*` settings (default and bounds, enforced by the database, on `orazaka_runtime_config`) all off and the sources
`law-corpus` and `donnees-quebec` disabled by default).

**External checks** (orazaka-packs#138): the engine's `orazaka.core.checks.courtrecords`, `.credit` and `.identity`
run here through one executor, handler key `check.external` (`ExternalCheckStrategy`), on the engine's
`ExternalCheckService` and the mock connector until the pilot's go. Step inputs `subject` and `consentedPurposes` (a
purpose is granted only when listed); outputs `outcome`, `refusal`, `category`, `providerRef`, `calls` and
`resultJson`; consumption `{calls: 0|1}` (0 releases the hold, 1 settles one `CALL` of `EXTERNAL_CHECK`). The job's
own hold covers the call (no second hold); a job with no hold is refused `BUDGET_EXHAUSTED`. Every call is audited in
`external_check_audit` (append-only, the subject as a keyed HMAC, no personal data). Fails closed until an operator
seals `checks.subject-pepper` — the seed leaves it absent. An input off the contract fails as `INPUT_INVALID`
(`InvalidJobInputException`). `ExecutorRegistryFitnessTest` [EXEC-003] keeps the seeded `handler_key`s and the
executors in step, both ways.

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

---

<sub>A Krizaka product · [krizaka.com](https://www.krizaka.com) · Apache-2.0</sub>
