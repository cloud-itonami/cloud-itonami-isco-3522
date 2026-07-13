# cloud-itonami-isco-3522

Open Business Blueprint for **ISCO-08 3522**: Telecommunications Engineering Technicians — an ISCO
**Wave 0 (cognitive substrate)** occupation per ADR-2607121000:
pure-cognitive work, the LLM-first wave, **no robotics gate** —
eligible for actor implementation now.

**Maturity: `:implemented`** — TelecomEngineeringTechniciansAdvisor ⊣
TelecomEngineeringTechniciansGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
13 tests / 28 assertions green. Cognitive scope only: this actor
approves REMOTE test-result records (network planning, monitoring,
fault triage) — no physical plant work.

The link-test HARD invariants — arithmetic and interval containment,
not eyeballed:

1. **Attenuation ceiling** — the proposed measured attenuation must
   not exceed the link's registered maximum.
2. **Calibration validity** — the proposed as-of day must not exceed
   the link's registered calibration-expiry-day — a test result from
   expired calibration equipment is not evidence, it's a guess.

Also HARD: unregistered/foreign link, unregistered organization,
non-`:propose` effect. Escalations (always human sign-off):
`:approve-service-restoral` (bringing a previously-failed link back
into production carriage), low confidence (< 0.6).

Cognitive scope only: network planning, monitoring, fault triage. Physical plant work (climbing, splicing, tower work) is robotics/field territory and NOT covered by this wave-0 blueprint.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
