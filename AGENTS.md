# orazaka-job-service — Governance scope (agent-neutral)

> This repository is one component of the **Orazaka platform**. The normative contract is
> [`AGENTS.md`](https://github.com/krizaka/orazaka/blob/main/AGENTS.md) at the root of the Orazaka workspace
> ([`krizaka/orazaka`](https://github.com/krizaka/orazaka)), together with its `.agent/rules/*`. When this repository
> is cloned inside the workspace (`orazaka-apps/services/orazaka-job-service`), that contract is loaded first and applies
> without exception. **No rule lives here** — this file only scopes it.

## Scope of this repository

- **Role:** Asynchronous job executor of the Orazaka engine: drains the interactive and batch lanes, owns the capability registry and the worker registry.
- **Layer:** Orazaka AI engine
- **Depends on:** orazaka-build, orazaka-contracts, krizaka-users, krizaka-billing, orazaka-ai-engine — never on another repository's Tier-3 implementation (AGENTS.md §2, [SEAM-002]).
- **Workspace path:** `orazaka-apps/services/orazaka-job-service`

## Definition of done

1. `./mvnw verify` is green (unit + Testcontainers ITs + this repository's `*GovernanceTest`).
2. Inside the workspace, the cross-repository rules are green as well
   (`./mvnw -f orazaka-apps/services/orazaka-job-service/pom.xml verify` from the workspace root).
3. Spotless (google-java-format) passes — it is bound to `validate`.
