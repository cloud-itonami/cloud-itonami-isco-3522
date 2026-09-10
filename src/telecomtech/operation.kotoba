(ns telecomtech.operation
  "The closed vocabulary of operations the ISCO-08 3522 telecommunications
  engineering technicians actor may propose.

  Runtime: portable `.cljc` (pure data + pure predicates, no host interop).

  Why this namespace exists. Before it, the operation vocabulary lived in two
  places that could not disagree loudly: the README's prose, and the
  Governor's private `(= :approve-test op)` test plus one named escalating op.
  That made the Governor a *denylist* — it bound one named op and admitted
  everything else. Measured on the pre-change tree, against a registered
  client and its registered link:

      {:op :cut-over-production-fiber :effect :propose :link-id \"L1\"
       :measured-attenuation-db 99.0 :as-of-day 500 :confidence 0.95}
      => {:ok? true :violations []}

  A 99 dB reading against a registered 3.5 dB ceiling, taken 400 days after
  the calibration expired, on an operation naming a production cut-over —
  admitted, with nothing to show a reviewer. `:climb-tower` and
  `:disconnect-emergency-line` were admitted the same way, and so was a
  proposal whose `:op` was `nil`.

  An actor whose operation set is open cannot be governed, because the
  governor is answering a question about a vocabulary nobody declared. So the
  vocabulary is declared here, once, as an allowlist, and
  `telecomtech.governor` refuses anything outside it.

  Two disjoint maps:

  * `supported` — what the actor may propose. `:escalates?` and `:link-op?`
    are properties of the operation, not of the governor's mood, so they live
    beside it.
  * `reserved` — operations naming authority this cognitive actor does not
    hold: physical plant work, and statutory carrier obligations. These are
    *declared* rather than merely absent so the refusal can say why. An
    undeclared op is a vocabulary error; a reserved op is an authority
    boundary. Conflating them would let a future edit `supported`-list one of
    them by accident.

  `:link-op?` is the field that closes the gap this repo shipped with. The
  attenuation ceiling, the calibration window and the registered-link basis
  were all gated on `(= :approve-test op)`, so `:approve-service-restoral` —
  the operation whose entire purpose is returning a previously FAILED link to
  production carriage — was exempt from all three. Measured on the pre-change
  tree, a restoral of an unregistered link at 99 dB against a 3.5 dB ceiling,
  400 days past calibration expiry, escalated to a human with an **empty
  violation list**. The human signing it off was shown nothing to weigh, on
  the one operation whose premise is that the link already failed.

  Binding is a property of the operation, so it is declared here and the
  governor reads it, rather than the governor naming one op and forgetting
  the more dangerous one."
  )

(def supported
  "Operations the actor may propose.

  `:escalates?` true means human sign-off is required regardless of advisor
  confidence. `:link-op?` true means the proposal binds to a REGISTERED link,
  and therefore must satisfy every registered fact about it — ownership, the
  attenuation ceiling, and the calibration validity window."
  {:draft-test-plan
   {:escalates? false
    :link-op? false
    :summary "draft a link test plan for the responsible engineer's review (binds nothing)"}

   :approve-test
   {:escalates? false
    :link-op? true
    :summary "approve a measured link test result for a registered link"}

   :approve-service-restoral
   {:escalates? true
    :link-op? true
    :summary "return a previously-failed registered link to production carriage"}

   :flag-fault
   {:escalates? true
    :link-op? false
    :summary "surface a suspected fault to the responsible field engineer"}})

(def reserved
  "Operations reserved to someone this actor is not. Naming one in a proposal
  is a permanent hard block, never an escalation: escalation would imply a
  human could approve the *actor* doing it, and neither a technician nor an
  operator can delegate physical plant work or a carrier's statutory duty to a
  remote cognitive actor.

  This is the machine-readable form of the scope sentence the README has
  carried since the repo was created — cognitive work only; climbing,
  splicing and tower work are field territory. Prose in a README does not
  refuse anything."
  {:climb-tower
   {:reason "a tower climb is a certified rigger's physical task, performed on site"}

   :splice-fiber
   {:reason "a splice is physical plant work performed by a certified field technician"}

   :cut-over-production-fiber
   {:reason "a production cut-over physically interrupts live carriage; the decision belongs to the field engineer on site"}

   :disconnect-emergency-line
   {:reason "disconnecting an emergency-services line is the licensed operator's statutory obligation"}})

(defn supported? [op] (contains? supported op))
(defn reserved? [op] (contains? reserved op))

(defn declared?
  "True if `op` is named anywhere in this vocabulary. An op that is neither
  supported nor reserved is undeclared — the governor refuses it."
  [op]
  (or (supported? op) (reserved? op)))

(defn escalates?
  "True if the operation itself always requires human sign-off. Unsupported
  ops are never reached by this predicate (the governor hard-blocks first), so
  a false here is not an admission."
  [op]
  (boolean (get-in supported [op :escalates?])))

(defn link-op?
  "True if the operation binds to a registered link and must therefore satisfy
  every registered fact about it. False for undeclared and reserved ops, which
  the governor hard-blocks before this is consulted."
  [op]
  (boolean (get-in supported [op :link-op?])))

(defn reserved-reason [op] (get-in reserved [op :reason]))
