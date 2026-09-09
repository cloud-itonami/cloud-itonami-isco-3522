(ns telecomtech.facts
  "Well-formedness of the values the ISCO-08 3522 telecommunications
  engineering technicians actor governs: the client record, the registered
  link record, and the proposal envelope.

  Runtime: portable `.cljc` (pure predicates, no host interop). Deliberately
  no `clojure.string` dependency — `blank?` is spelled out below so this
  namespace adds no coordinate to `deps.edn`.

  Why this namespace exists — five measurements on the pre-change tree, all
  against a registered client and a registered link whose ceiling was 3.5 dB
  and whose calibration expired on day 100.

  1. The attenuation ceiling was guarded by `(number? measured-attenuation-db)`
     and the calibration window by `(integer? as-of-day)`, so the two easiest
     inputs available skipped the comparisons entirely:

         <approve-test, no :measured-attenuation-db>  => {:ok? true}
         <approve-test, :measured-attenuation-db \"99\"> => {:ok? true}
         <approve-test, no :as-of-day>                => {:ok? true}
         <approve-test, :as-of-day \"500\">             => {:ok? true}

     A HARD invariant that a missing field switches off is not a ceiling. For
     a link test the measurement *is* the evidence, so its absence is the
     violation rather than the reason not to check.

  2. A negative insertion loss passed:

         <approve-test, :measured-attenuation-db -50.0> => {:ok? true}

     A passive link cannot amplify. -50 dB is not a low reading, it is a
     reading from an instrument that was not measuring this link, and it
     compared as comfortably under the ceiling.

  3. Client provenance was written as `(nil? client-record)`. That asks
     whether the store returned something, not whether that something
     identifies a client. Registering the empty map put a record under the key
     `nil`, after which a request carrying no `:client-id` resolved to it:

         (register-client! s {})
         (governor/check {} {} <clean test approval> s)
         => {:ok? true :violations []}

     `nil?` is a fact about the store's return value. Provenance is a fact
     about the record. Those are different questions, and the second one needs
     a place to live.

  4. The *registered* record was trusted as blindly as the proposal. A link
     registered without a `:max-attenuation-db` made the governor throw rather
     than refuse:

         (register-link! s {:link-id \"L1\" :client-id \"C1\"})
         (governor/check ...) => java.lang.NullPointerException

     A crash is not a refusal. It fails the request, but in a shape no caller
     can audit — and on the `:cljs` runtime the same comparison answers
     `false` silently instead, which *admits* the proposal. The same tree,
     the same registration defect, opposite outcomes per host. A registered
     record that cannot be compared against is a defect in the registration,
     and is reported as one.

  5. `:confidence` is compared against the governor's floor to decide
     escalation, but nothing constrained it:

         <approve-test, :confidence 99.0>   => {:ok? true}   ; not escalated
         <approve-test, :confidence \"high\"> => ClassCastException

     A missing confidence already reads as 0.0 in the governor and therefore
     escalates, which is the safe direction and is left alone. A present but
     unusable confidence is the unsafe direction: it either buys the advisor
     out of escalation with a number that means nothing, or it crashes."
  (:require [telecomtech.operation :as op]))

(defn- blank?
  "True for nil, a non-string, or a string of only whitespace. Spelled out
  rather than pulled from `clojure.string` so this namespace stays
  dependency-free."
  [s]
  (or (not (string? s))
      (every? #(contains? #{\space \tab \newline \return} %) s)))

(defn- usable-number?
  "True for a real, finite number: the bounds exclude the infinities, and they
  exclude NaN too — every comparison against NaN is false, so `(> ##NaN -1e15)`
  is already false. No host interop, so `:clj` and `:cljs` agree.

  That NaN falls out of the *bounds* rather than out of a dedicated clause is
  worth stating, because it is the reason the clause has to be here at all.
  Every invariant in this governor is an inequality, and NaN makes every
  inequality false: a NaN attenuation is never over the ceiling, and a NaN
  confidence is never below the floor. NaN does not fail these checks, it
  disappears from them.

  This function shipped with an explicit `(== x x)` NaN guard in front of the
  bounds. Mutation testing showed it was dead — removing it reddened nothing,
  because the bounds already did that work — while the docstring credited it
  for the rejection. A clause that cannot fail, described as the one doing the
  work, is the failure mode this workspace keeps finding; it is recorded here
  rather than quietly deleted. The behaviour is pinned by
  `facts-test/usable-attenuation-rejects-what-cannot-be-compared` and by the
  `bounds-removed` mutation, which is load-bearing where `(== x x)` was not."
  [x]
  (and (number? x)
       (> x -1e15)
       (< x 1e15)))

(defn usable-attenuation?
  "True for a value that can stand as a measured insertion loss in dB: a
  finite, non-negative number. Negative is rejected because a passive link
  cannot amplify — a negative reading is an instrument fault, not a good
  result, and it compares as under any ceiling."
  [x]
  (and (usable-number? x) (not (neg? x))))

(defn usable-day?
  "True for a value that can stand as a day on this link's monotonic day
  clock: a non-negative integer."
  [x]
  (and (integer? x) (not (neg? x))))

(defn usable-confidence?
  "True for a confidence that can be compared against the governor's floor: a
  finite number in [0, 1]."
  [x]
  (and (usable-number? x) (<= 0 x) (<= x 1)))

(defn provenance-violations
  "The store's answer must actually identify the client the request names.
  Two separate questions, because a record that exists is not a record that
  belongs to this requester."
  [request client-record]
  (cond
    (nil? client-record)
    [{:rule :no-client :detail "未登録 client"}]

    (blank? (:client-id client-record))
    [{:rule :client-record-unidentified
      :detail "登録済み client record が :client-id を持たない（store が何かを返したことと、それが client であることは別の問い）"}]

    (not= (:client-id client-record) (:client-id request))
    [{:rule :client-record-mismatch
      :detail (str "store が返した client-id " (pr-str (:client-id client-record))
                   " が request の " (pr-str (:client-id request)) " と一致しない")}]

    :else []))

(defn vocabulary-violations
  "The operation must be one this repo declared. Undeclared and reserved are
  reported as different rules on purpose — see `telecomtech.operation`."
  [proposal]
  (let [o (:op proposal)]
    (cond
      (op/reserved? o)
      [{:rule :no-field-authority :detail (op/reserved-reason o)}]

      (not (op/supported? o))
      [{:rule :undeclared-operation
        :detail (str "operation " (pr-str o) " は telecomtech.operation/supported に無い"
                     "（宣言されていない語彙に対して governor は答えを持てない）")}]

      :else [])))

(defn link-binding-violations
  "For an operation that binds to a registered link, the proposal must
  actually name the link, the day it proposes to measure on, and a usable
  measurement.

  These are required rather than optional because each hard invariant is a
  comparison against them: without a usable value there is no comparison to
  make, and the pre-change tree resolved that by not comparing — which
  admitted the proposal. Absence is the violation."
  [proposal]
  (let [o (:op proposal)]
    (if-not (op/link-op? o)
      []
      (cond-> []
        (blank? (:link-id proposal))
        (conj {:rule :link-not-named
               :detail (str "operation " o " は登録済み link に束縛されるが link を名指していない")})

        (not (usable-day? (:as-of-day proposal)))
        (conj {:rule :as-of-day-unusable
               :detail (str "operation " o " は校正有効期限と比較する非負整数の :as-of-day を要する"
                            "（日が無いことは、窓を検査しない理由にならない）")})

        (not (usable-attenuation? (:measured-attenuation-db proposal)))
        (conj {:rule :attenuation-unusable
               :detail (str "operation " o " は有限かつ非負の :measured-attenuation-db を要する"
                            "（測定が無いことは、上限を検査しない理由にならない。"
                            "負値は低い測定値ではなく計器異常であり、どんな上限も下回る）")})))))

(defn link-record-violations
  "The REGISTERED record must be comparable. A link registered without a
  ceiling or without a calibration expiry cannot be governed, and the
  pre-change tree answered that by throwing on `:clj` and by silently
  admitting on `:cljs`. A registration defect is reported as a registration
  defect."
  [op-kw l]
  (if-not (and (op/link-op? op-kw) (some? l))
    []
    (cond-> []
      (not (usable-attenuation? (:max-attenuation-db l)))
      (conj {:rule :link-record-no-ceiling
             :detail (str "登録済み link " (pr-str (:link-id l))
                          " が使用可能な :max-attenuation-db を持たない"
                          "（比較できない登録は登録の欠陥であって、承認の理由ではない）")})

      (not (usable-day? (:calibration-expiry-day l)))
      (conj {:rule :link-record-no-calibration
             :detail (str "登録済み link " (pr-str (:link-id l))
                          " が使用可能な :calibration-expiry-day を持たない")}))))

(defn confidence-violations
  "A present-but-unusable confidence is a hard block. An ABSENT confidence is
  not: the governor reads it as 0.0, which escalates, and escalating is the
  safe direction. Only a value that claims to be a confidence and cannot be
  compared is refused here."
  [proposal]
  (let [c (:confidence proposal)]
    (if (or (nil? c) (usable-confidence? c))
      []
      [{:rule :confidence-unusable
        :detail (str ":confidence " (pr-str c) " は [0,1] の有限数ではない"
                     "（比較できない自信度は、escalation を買い取る根拠にならない）")}])))
