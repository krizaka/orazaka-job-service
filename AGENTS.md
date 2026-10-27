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

## Static rules & codemods (OpenRewrite, ArchUnit)

The org code rules (addendum 2026-10-15) are checked by tools, not by review.

- **OpenRewrite** (`rewrite-maven-plugin`, configured once in `krizaka-parent`, inherited by every JVM repository):
  common static analysis, test best practices, Java 21 idioms, Spring idioms (no `@Autowired` on a constructor; `@Bean` methods stay public), no duplicate POM dependency. `./mvnw rewrite:dryRun` lists what would change and **fails** if
  anything would; `./mvnw rewrite:run` applies it — behaviour
  identical, run the tests, land it in a small pull request. A recipe that is wrong for this repository is excluded here
  (`<exclusions>` / a `rewrite.yml`), with the reason; test fixtures (`**/fixture/**`) are excluded everywhere.
- **ArchUnit** (shared rules in `krizaka-test-support`: `CodeRules`, `ConfigBindingRules`, `SourceRules`,
  `ArchitectureRules`): the governance tests of a module call them. `ArchitectureRules` holds the structure — layers
  (web → service → repository, none skipped, none calling up), no package cycle, `*Controller` / `*Service` /
  `*Repository` names, `@Value` and the environment read only in configuration, `@Transactional` only on services. The
  older `CodeRules` hold the rest (no field injection, no `System.out`, domain free of framework, one class per file…).
- **Debt only shrinks — `FrozenRules`** (ArchUnit `FreezingArchRule`): a rule a module cannot satisfy yet is checked with
  `FrozenRules.check(ArchitectureRules.xxxRule(...), CLASSES)`. The violations that exist are recorded in the module's
  `archunit_store/` (committed); a **new** violation fails, a fixed one leaves the store (commit it). A rule with no store
  fails: record a new rule's debt once with `-Darchunit.freeze.store.default.allowStoreCreation=true`, review the store,
  commit it. Never add a violation to a store by hand to get a build green.
- A rule never passes over nothing (no `allowEmptyShould`, Orazaka GOV-006): a module applies the rules whose subject it has (a library without controllers does not apply the controller naming rule).
- A rule is never weakened, skipped or deleted to get a PR green (AGENTS §5.3 of the org); fix the code or freeze the existing debt.

## Definition of done

1. `./mvnw verify` is green (unit + Testcontainers ITs + this repository's `*GovernanceTest`).
2. Inside the workspace, the cross-repository rules are green as well
   (`./mvnw -f orazaka-apps/services/orazaka-job-service/pom.xml verify` from the workspace root).
3. Spotless (google-java-format) passes — it is bound to `validate`.
4. The change ships with its tests — a unit test for logic, a Testcontainers integration test for an adapter, an
   endpoint or a flow; a fix starts with a failing test that reproduces the bug. CI runs them and must be green; no
   test = not done (workspace [`AGENTS.md` §9](https://github.com/krizaka/orazaka/blob/main/AGENTS.md#9-testing)).

**Repository layout:** the root follows [krizaka/.github `docs/standards/repo-layout.md`](https://github.com/krizaka/.github/blob/main/docs/standards/repo-layout.md); the CI guard `check-repo-layout` fails on a stray root file, and a justified extra is declared in `.repo-layout.json` with its why.
