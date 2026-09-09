(ns telecomtech.sim
  "Deterministic governed-scenario harness for the ISCO-08 3522
  telecommunications engineering technicians actor: run a table of requests
  through the real StateGraph and report which ones the governor refused.

  Runtime: `run` and `report` are portable `.cljc`. `-main` is `:clj`-only,
  because process exit codes are a host concern; the `:cljs` branch throws
  rather than pretending to exit.

  Why this namespace exists, and why it fails loudly. A governed actor's
  claim is not that it acts — it is that there exist actions it refuses. A
  harness that ran only clean scenarios would print green while demonstrating
  nothing, which is the shape this workspace has repeatedly caught: a check
  that could not fail returning the same value as a check that passed.

  So `run` counts refusals, and `-main` exits non-zero when the count is zero.
  A scenario table that has stopped exercising the governor is a defect in the
  table, and it is reported as one rather than as a pass.

  The three questions this harness answers that a unit test does not:
    * does the *wired graph* refuse, or only the pure `check` function
    * does an escalated request actually interrupt rather than write
    * does the ledger it leaves behind verify, and does it record who
      approved each write

  Every scenario below is one of the refusals measured as MISSING on the
  pre-change tree — see the docstrings of `telecomtech.operation` and
  `telecomtech.facts` for those measurements. This table is the standing
  evidence that they are refusals now."
  (:require [telecomtech.actor :as actor]
            [telecomtech.advisor :as advisor]
            [telecomtech.ledger :as led]
            [telecomtech.phase :as phase]
            [telecomtech.store :as store]))

(def registered-client
  {:client-id "sim-client-1" :name "Awai Community Telecom"})

(def other-client
  {:client-id "sim-client-2" :name "Another Carrier"})

(def registered-link
  {:link-id "L-1" :client-id "sim-client-1" :name "fibre-north"
   :max-attenuation-db 3.5
   :calibration-expiry-day 400})

(def other-clients-link
  {:link-id "L-2" :client-id "sim-client-2" :name "fibre-south"
   :max-attenuation-db 3.5
   :calibration-expiry-day 400})

(def incompletely-registered-link
  "Registered with neither a ceiling nor a calibration expiry. On the
  pre-change tree this made the governor throw on `:clj` and silently admit on
  `:cljs` — the same tree, opposite outcomes per host."
  {:link-id "L-BAD" :client-id "sim-client-1" :name "fibre-unregistered-facts"})

(defn- tweaking-advisor
  "An advisor that proposes as the mock does, then applies `f` to the
  proposal. Used to reach proposal shapes a well-formed request cannot
  produce — an unusable confidence, a string where a measurement belongs."
  [f]
  (let [inner (advisor/mock-advisor)]
    (reify advisor/Advisor
      (-advise [_ store request] (f (advisor/-advise inner store request))))))

(def scenarios
  "Each entry: the request, the phase it must reach, and why.

  `:expect` is the phase, not merely 'refused', so a scenario that starts
  escalating instead of holding is a mismatch rather than a silent pass —
  the difference between 'no human may approve this' and 'a human must'."
  [;; ── clean baseline: without these the table proves only that it says no ──
   {:name :clean-test-commits
    :request {:client-id "sim-client-1" :op :approve-test :link-id "L-1"
              :measured-attenuation-db 2.1 :as-of-day 200}
    :expect :commit
    :why "a conforming reading on a registered, in-calibration link is written"}

   {:name :draft-binds-nothing-commits
    :request {:client-id "sim-client-1" :op :draft-test-plan}
    :expect :commit
    :why "a draft binds no link and needs no measurement"}

   ;; ── vocabulary: the op set is closed ─────────────────────────────────────
   {:name :undeclared-op-holds
    :request {:client-id "sim-client-1" :op :reroute-backhaul :link-id "L-1"
              :measured-attenuation-db 2.1 :as-of-day 200}
    :expect :hold
    :why "an op nobody declared cannot be governed; pre-change it committed"}

   {:name :reserved-field-work-holds
    :request {:client-id "sim-client-1" :op :climb-tower}
    :expect :hold
    :why "a tower climb is field work; the README said so and nothing enforced it"}

   {:name :reserved-cut-over-holds
    :request {:client-id "sim-client-1" :op :cut-over-production-fiber :link-id "L-1"
              :measured-attenuation-db 99.0 :as-of-day 500}
    :expect :hold
    :why "pre-change this exact proposal returned {:ok? true :violations []}"}

   ;; ── the restoral exemption: escalating is not the same as governed ───────
   {:name :restoral-over-ceiling-holds
    :request {:client-id "sim-client-1" :op :approve-service-restoral :link-id "L-1"
              :measured-attenuation-db 99.0 :as-of-day 200}
    :expect :hold
    :why "pre-change a restoral 95dB over the ceiling escalated with an EMPTY violation list"}

   {:name :restoral-expired-calibration-holds
    :request {:client-id "sim-client-1" :op :approve-service-restoral :link-id "L-1"
              :measured-attenuation-db 2.1 :as-of-day 900}
    :expect :hold
    :why "a restoral measured 500 days past calibration expiry was exempt from the window"}

   {:name :restoral-unknown-link-holds
    :request {:client-id "sim-client-1" :op :approve-service-restoral :link-id "L-NOPE"
              :measured-attenuation-db 2.1 :as-of-day 200}
    :expect :hold
    :why "a restoral of a link nobody registered was exempt from the registered-link basis"}

   {:name :restoral-other-clients-link-holds
    :request {:client-id "sim-client-1" :op :approve-service-restoral :link-id "L-2"
              :measured-attenuation-db 2.1 :as-of-day 200}
    :expect :hold
    :why "a restoral of another carrier's link was exempt from the ownership check"}

   {:name :clean-restoral-escalates
    :request {:client-id "sim-client-1" :op :approve-service-restoral :link-id "L-1"
              :measured-attenuation-db 2.1 :as-of-day 200}
    :expect :request-approval
    :why "a restoral that breaches nothing is still a human's decision, not a block"}

   ;; ── unusable values: the guard must not be the off switch ────────────────
   {:name :missing-measurement-holds
    :request {:client-id "sim-client-1" :op :approve-test :link-id "L-1"
              :as-of-day 200}
    :expect :hold
    :why "pre-change (number? x) turned the ceiling off when the field was absent"}

   {:name :string-measurement-holds
    :request {:client-id "sim-client-1" :op :approve-test :link-id "L-1"
              :measured-attenuation-db "99" :as-of-day 200}
    :expect :hold
    :why "a string skipped the comparison and committed"}

   {:name :missing-day-holds
    :request {:client-id "sim-client-1" :op :approve-test :link-id "L-1"
              :measured-attenuation-db 2.1}
    :expect :hold
    :why "pre-change (integer? x) turned the calibration window off when the day was absent"}

   {:name :negative-attenuation-holds
    :request {:client-id "sim-client-1" :op :approve-test :link-id "L-1"
              :measured-attenuation-db -50.0 :as-of-day 200}
    :expect :hold
    :why "a passive link cannot amplify; -50dB is an instrument fault that undercuts any ceiling"}

   ;; ── the registered record is a value too ─────────────────────────────────
   {:name :unusable-link-record-holds
    :request {:client-id "sim-client-1" :op :approve-test :link-id "L-BAD"
              :measured-attenuation-db 2.1 :as-of-day 200}
    :expect :hold
    :why "pre-change this threw on :clj and admitted on :cljs; a crash is not a refusal"}

   ;; ── confidence ──────────────────────────────────────────────────────────
   {:name :unusable-confidence-holds
    :request {:client-id "sim-client-1" :op :approve-test :link-id "L-1"
              :measured-attenuation-db 2.1 :as-of-day 200}
    :tweak #(assoc % :confidence 99.0)
    :expect :hold
    :why "pre-change 99.0 bought the advisor out of escalation with a number meaning nothing"}

   {:name :low-confidence-escalates
    :request {:client-id "sim-client-1" :op :approve-test :link-id "L-1"
              :measured-attenuation-db 2.1 :as-of-day 200}
    :tweak #(assoc % :confidence 0.2)
    :expect :request-approval
    :why "confidence below the floor is a question for a human"}

   ;; ── provenance and actuation ────────────────────────────────────────────
   {:name :unregistered-client-holds
    :request {:client-id "sim-client-999" :op :approve-test :link-id "L-1"
              :measured-attenuation-db 2.1 :as-of-day 200}
    :expect :hold
    :why "an unregistered organisation has no standing to approve a test result"}

   {:name :direct-actuation-holds
    :request {:client-id "sim-client-1" :op :approve-test :link-id "L-1"
              :measured-attenuation-db 2.1 :as-of-day 200}
    :tweak #(assoc % :effect :write)
    :expect :hold
    :why "the advisor may only propose; a direct write is never admitted"}])

(defn- run-one [scenario]
  (let [st (store/mem-store)
        _ (store/register-client! st registered-client)
        _ (store/register-client! st other-client)
        _ (store/register-link! st registered-link)
        _ (store/register-link! st other-clients-link)
        _ (store/register-link! st incompletely-registered-link)
        graph (actor/build-graph
               (cond-> {:store st}
                 (:tweak scenario) (assoc :advisor (tweaking-advisor (:tweak scenario)))))
        thread (str "sim-" (name (:name scenario)))
        result (actor/run-request! graph (:request scenario) {} thread)
        state (:state result)
        actual (or (:disposition state)
                   ;; A run that never reached :decide produced no phase at
                   ;; all; report that rather than defaulting it to a phase,
                   ;; which would make an unrun scenario look like a verdict.
                   :no-phase)]
    {:name (:name scenario)
     :expect (:expect scenario)
     :actual actual
     :why (:why scenario)
     :status (:status result)
     :match? (= actual (:expect scenario))
     :refusal? (and (not= actual :no-phase) (phase/refusal? actual))
     :wrote? (pos? (count (store/records-of st (:client-id (:request scenario)))))
     :ledger-verify (led/verify (store/ledger st))}))

(defn run
  "Run every scenario. Returns
  `{:results [..] :refusals n :mismatches [..] :ledger-breaks [..] :ok? bool}`.

  `:ok?` requires all four: every scenario reached its expected phase, no
  refusal wrote a record anyway, every ledger left behind verifies, and at
  least one refusal was demonstrated."
  []
  (let [results (mapv run-one scenarios)
        refusals (count (filter :refusal? results))
        mismatches (filterv (complement :match?) results)
        ;; A refusal that still wrote a record is the worst outcome available
        ;; and would otherwise hide inside a matching phase.
        wrote-anyway (filterv #(and (:refusal? %) (:wrote? %)) results)
        ledger-breaks (filterv #(not (:ok? (:ledger-verify %))) results)]
    {:results results
     :refusals refusals
     :mismatches mismatches
     :wrote-anyway wrote-anyway
     :ledger-breaks ledger-breaks
     :ok? (and (empty? mismatches)
               (empty? wrote-anyway)
               (empty? ledger-breaks)
               (pos? refusals))}))

(defn report
  "Human-readable run report. Pure: takes the result of `run`."
  [{:keys [results refusals mismatches wrote-anyway ledger-breaks ok?]}]
  (str
   "telecomtech.sim — governed scenario run\n"
   (apply str
          (for [r results]
            (str "  " (if (:match? r) "ok  " "BAD ")
                 (name (:name r))
                 " expect=" (name (:expect r))
                 " actual=" (name (:actual r))
                 (when (:refusal? r) " [refused]")
                 "\n")))
   "  scenarios=" (count results)
   " refusals=" refusals
   " mismatches=" (count mismatches)
   " wrote-anyway=" (count wrote-anyway)
   " ledger-breaks=" (count ledger-breaks)
   "\n"
   (cond
     (zero? refusals)
     "  REFUSING TO REPORT A PASS: the scenario table demonstrated no refusal.\n"
     ok? "  PASS\n"
     :else "  FAIL\n")))

#?(:clj
   (defn -main [& _]
     (let [r (run)]
       (print (report r))
       (flush)
       (System/exit (if (:ok? r) 0 1))))
   :cljs
   (defn -main [& _]
     (throw (ex-info "telecomtech.sim/-main is :clj-only (process exit codes are a host concern); call `run` and inspect the result instead" {}))))
