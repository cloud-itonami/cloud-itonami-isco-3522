# cloud-itonami-isco-3522

Open Business Blueprint for **ISCO-08 3522**: Telecommunications Engineering Technicians — an ISCO
**Wave 0 (cognitive substrate)** occupation per ADR-2607121000:
pure-cognitive work, the LLM-first wave, **no robotics gate** —
eligible for actor implementation now.

**Maturity: `:implemented`** — TelecomEngineeringTechniciansAdvisor ⊣
TelecomEngineeringTechniciansGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
89 tests / 350 assertions green, plus a 19-scenario governed run.
Cognitive scope only: this actor approves REMOTE test-result records
(network planning, monitoring, fault triage) — no physical plant work.

## Namespaces

| namespace | what it owns |
|---|---|
| `telecomtech.operation` | the closed vocabulary — which ops exist, which escalate, which bind to a link, which are reserved to a field technician |
| `telecomtech.facts` | whether the values — proposed *and registered* — can be compared at all |
| `telecomtech.governor` | the comparisons themselves; the independent refusal layer |
| `telecomtech.advisor` | proposes; never decides |
| `telecomtech.phase` | verdict → `:hold` / `:request-approval` / `:commit`, and what each may do |
| `telecomtech.ledger` | hash-chained append-only audit entries, and their verification |
| `telecomtech.store` | the SSoT: clients, links, records, ledger |
| `telecomtech.actor` | the wired StateGraph |
| `telecomtech.sim` | the governed-scenario harness (`clojure -M:sim`) |

## What the governor refuses

HARD invariants (always `:hold`, never overridable):

1. **Vocabulary** — the op must be declared in
   `telecomtech.operation/supported`. A *reserved* op (tower climb, splice,
   production cut-over, disconnecting an emergency line) is reported as an
   authority boundary, separately from an *undeclared* one.
2. **Client provenance** — the store's record must identify the client the
   request names.
3. **No actuation** — `:effect` must be `:propose`.
4. **Link basis** — an operation that binds to a link must cite a REGISTERED
   link belonging to this client, and carry a usable measurement and day.
5. **Registered record** — the link's own ceiling and calibration expiry must
   themselves be comparable.
6. **Attenuation ceiling** — the measured attenuation must not exceed the
   link's registered maximum. Arithmetic, not eyeballed.
7. **Calibration validity** — the as-of day must not exceed the link's
   registered calibration-expiry day. A test result from expired calibration
   equipment is not evidence, it's a guess.
8. **Usable confidence** — a present `:confidence` must be a number in [0,1].

ESCALATION (human sign-off): operations declaring `:escalates?` —
`:approve-service-restoral` and `:flag-fault` — and confidence below 0.6.
`:hard?` is checked *before* `:escalate?`: a proposal that is both must hold,
because escalating it would ask a human to approve something no human may
approve.

## The refusals this repo shipped without

Measured on the tree before 2026-09-10, against a registered client and its
registered link (ceiling 3.5 dB, calibration expiring day 100):

| proposal | pre-change verdict |
|---|---|
| `:cut-over-production-fiber`, 99 dB, day 500 | `{:ok? true :violations []}` |
| `:climb-tower` | `{:ok? true :violations []}` |
| `:op nil` | `{:ok? true :violations []}` |
| `:approve-service-restoral`, 99 dB | escalated, `:violations []` |
| `:approve-service-restoral`, day 500 | escalated, `:violations []` |
| `:approve-service-restoral`, unregistered link | escalated, `:violations []` |
| `:approve-service-restoral`, another carrier's link | escalated, `:violations []` |
| `:approve-test`, no `:measured-attenuation-db` | `{:ok? true}` |
| `:approve-test`, `:measured-attenuation-db "99"` | `{:ok? true}` |
| `:approve-test`, no `:as-of-day` | `{:ok? true}` |
| `:approve-test`, `:measured-attenuation-db -50.0` | `{:ok? true}` |
| empty client `{}` registered, request with no `:client-id` | `{:ok? true}` |
| link registered with no ceiling | `NullPointerException` on `:clj`, admitted on `:cljs` |
| `:confidence 99.0` | not escalated |

Three causes, one shape. The governor bound `:approve-test` **by name**, so it
was a denylist and every other operation was ungoverned — including the
restoral, the one operation whose premise is that the link *already failed*.
The value checks were spelled `(number? x)` and `(integer? x)` **inline**, so
each HARD ceiling switched itself off exactly when the field it compared was
absent or the wrong type. And the *registered* record was trusted as blindly
as the proposal, which on `:clj` crashed and on `:cljs` admitted.

All fourteen are refusals now, and `telecomtech.sim` re-demonstrates them on
every run. A crash is not a refusal; neither is a check a missing field can
turn off.

Cognitive scope only: network planning, monitoring, fault triage. Physical plant work (climbing, splicing, tower work) is robotics/field territory and NOT covered by this wave-0 blueprint.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
