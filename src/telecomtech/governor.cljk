(ns telecomtech.governor
  "TelecomEngineeringTechniciansGovernor — the independent safety/
  traceability layer for the ISCO-08 3522 community telecommunications
  engineering technicians actor (itonami actor pattern, ADR-2607011000 /
  CLAUDE.md Actors section). Modeled on cloud-itonami-isco-4311's
  bookkeeping.governor. Test-technician twist: a measured attenuation is
  arithmetic comparison against the registered ceiling, and the test's as-of
  day must fall within the registered calibration validity window — a test
  result from expired calibration equipment is not evidence, it's a guess.

  Runtime: portable `.cljc`. Pure — `check` never mutates the store.

  The three layers this function composes, and why they are separate:

    telecomtech.operation  is this op in the declared vocabulary, and does
                           it bind to a link?
    telecomtech.facts      are the values — proposed AND registered — usable
                           for the comparisons about to be made?
    here                   the comparisons themselves.

  They are separate because the pre-change tree collapsed them, and each
  collapse admitted something. The vocabulary was implicit in
  `(= :approve-test op)`, which made every other op unbound — including a
  proposed production fibre cut-over at 99 dB against a 3.5 dB ceiling, which
  returned `{:ok? true :violations []}`. The value checks were spelled
  `(number? x)` and `(integer? x)` inline, which turned each HARD ceiling off
  whenever the field it compared was absent or a string. See the two
  namespaces' docstrings for the measurements.

  The invariant that binds the whole thing: **a check is never skipped
  silently.** Where a comparison cannot be made, `telecomtech.facts` has
  already recorded why as a violation of its own, so an unusable value
  produces a refusal rather than an admission.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. vocabulary        — the op must be declared in
                           `telecomtech.operation/supported`. A reserved op
                           (physical plant work, statutory carrier duty) is
                           reported separately from an undeclared one.
    2. client provenance — the store's record must identify the client the
                           request names.
    3. no-actuation      — proposal :effect must be :propose.
    4. link basis        — an operation that binds to a link must cite a
                           REGISTERED link belonging to this client, and must
                           carry a usable measurement and day.
    5. registered record — the link's registered ceiling and calibration
                           expiry must themselves be comparable.
    6. attenuation ceiling — the proposed measured-attenuation-db must not
                           exceed the link's registered
                           :max-attenuation-db (arithmetic, not eyeballed).
    7. calibration validity — the proposed as-of day must not exceed the
                           link's registered :calibration-expiry-day (expired
                           calibration equipment produces a guess, not
                           evidence).
    8. usable confidence — a present :confidence must be a number in [0,1].
  ESCALATION invariants (:escalate? true, human sign-off):
    9. the operation declares `:escalates?` — currently
                           :approve-service-restoral (returning a
                           previously-failed link to production carriage) and
                           :flag-fault.
   10. low confidence (< `confidence-floor`).

  Invariants 1, 4, 5, 6 and 7 now apply to EVERY link-binding operation, not
  only to `:approve-test`. That is the substantive change: a restoral used to
  reach a human with an empty violation list."
  (:require [telecomtech.facts :as facts]
            [telecomtech.operation :as op]
            [telecomtech.store :as store]))

(def confidence-floor 0.6)

(defn- link-invariant-violations
  "The comparisons proper, for operations that bind to a registered link.

  Each arithmetic comparison is guarded by BOTH sides being usable, but the
  guard is not a silent skip: `facts/link-binding-violations` and
  `facts/link-record-violations` have already reported an unusable value as a
  violation, so a guarded-out comparison never turns into an admission. That
  pairing is the whole reason those namespaces exist."
  [request proposal l]
  (let [{:keys [op measured-attenuation-db as-of-day]} proposal]
    (if-not (op/link-op? op)
      []
      (cond-> []
        (nil? l)
        (conj {:rule :unknown-link
               :detail (str "未登録 link への " op " は不可")})

        (and (some? l) (not= (:client-id l) (:client-id request)))
        (conj {:rule :link-wrong-client
               :detail "link が別 client のもの"})

        (and (some? l)
             (facts/usable-attenuation? measured-attenuation-db)
             (facts/usable-attenuation? (:max-attenuation-db l))
             (> measured-attenuation-db (:max-attenuation-db l)))
        (conj {:rule :attenuation-exceeds-ceiling
               :detail (str "測定減衰量 " measured-attenuation-db "dB > 登録済み上限 "
                            (:max-attenuation-db l) "dB（減衰量は測定であって目視ではない）")})

        (and (some? l)
             (facts/usable-day? as-of-day)
             (facts/usable-day? (:calibration-expiry-day l))
             (> as-of-day (:calibration-expiry-day l)))
        (conj {:rule :calibration-expired
               :detail (str "day " as-of-day " > 校正有効期限 " (:calibration-expiry-day l)
                            "（失効校正機器の測定結果は証拠ではなく推測）")})))))

(defn- hard-violations [request proposal client-record l]
  (vec (concat (facts/vocabulary-violations proposal)
               (facts/provenance-violations request client-record)
               (when (not= :propose (:effect proposal))
                 [{:rule :no-actuation
                   :detail "effect は :propose のみ許可（直接書込禁止）"}])
               (facts/link-binding-violations proposal)
               (facts/link-record-violations (:op proposal) l)
               (facts/confidence-violations proposal)
               (link-invariant-violations request proposal l))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `telecomtech.store/Store`. Pure — never mutates the store.

  Returns `{:ok? :violations :confidence :hard? :escalate?}`. `:hard?` is
  checked before `:escalate?` by every caller (see `telecomtech.phase`): a
  proposal that is both hard-blocked and escalating must hold, because
  escalating it would ask a human to approve something no human may approve."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        l (some->> (:link-id proposal) (store/link store))
        hard (hard-violations request proposal client-record l)
        hard? (boolean (seq hard))
        raw-conf (:confidence proposal)
        conf (if (facts/usable-confidence? raw-conf) raw-conf 0.0)
        low? (< conf confidence-floor)
        risky-op? (op/escalates? (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
