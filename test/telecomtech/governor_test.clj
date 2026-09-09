(ns telecomtech.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [telecomtech.store :as store]
            [telecomtech.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-link! st {:link-id "L-1" :client-id "client-1"
                              :name "backbone-run-12"
                              :max-attenuation-db 3.0
                              :calibration-expiry-day 400})
    st))

(defn- test-result [db day]
  {:op :approve-test :effect :propose :link-id "L-1"
   :measured-attenuation-db db :as-of-day day :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-within-attenuation-and-calibration-valid
  (let [st (fresh-store)
        v (governor/check req {} (test-result 2.1 200) st)]
    (is (:ok? v))))

(deftest ok-at-exact-ceiling-and-expiry-day
  (testing "the attenuation ceiling and calibration expiry boundaries are inclusive"
    (let [st (fresh-store)]
      (is (:ok? (governor/check req {} (test-result 3.0 200) st)))
      (is (:ok? (governor/check req {} (test-result 2.1 400) st))))))

(deftest hard-on-attenuation-exceeds-ceiling
  (testing "attenuation is measured, not eyeballed"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (test-result 5.0 200) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :attenuation-exceeds-ceiling (:rule %)) (:violations v))))))

(deftest hard-on-calibration-expired
  (testing "a test from expired calibration equipment is a guess, not evidence"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (test-result 2.1 500) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :calibration-expired (:rule %)) (:violations v))))))

(deftest hard-on-unknown-link
  (let [st (fresh-store)
        v (governor/check req {} (assoc (test-result 2.1 200) :link-id "L-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-link (:rule %)) (:violations v)))))

(deftest hard-on-foreign-link
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (test-result 2.1 200) st)]
      (is (:hard? v))
      (is (some #(= :link-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (test-result 2.1 200) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (test-result 2.1 200) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-service-restoral
  (testing "a restoral that breaches nothing is a human's decision, not a block"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-service-restoral :effect :propose
                                    :link-id "L-1" :measured-attenuation-db 2.1
                                    :as-of-day 200 :confidence 0.9 :stake :high} st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

;; This assertion used to read `{:op :approve-service-restoral :link-id "L-1"}`
;; with no measurement and no day, and expected a clean escalation. That was
;; the defect under test, written down as the contract: a restoral is the one
;; operation whose premise is that the link ALREADY FAILED, and it was the one
;; operation exempt from the ceiling, the calibration window and the
;; registered-link basis. Measured on the pre-change tree, a restoral of an
;; unregistered link at 99dB against a 3.5dB ceiling 500 days past calibration
;; expiry reached a human with `:violations []`.
;;
;; The pair below is the discriminating one: the same op escalates when it
;; carries evidence and holds when it does not.
(deftest hard-on-restoral-without-evidence
  (testing "a restoral naming no measurement and no day reaches a human with nothing to weigh"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-service-restoral :effect :propose
                                    :link-id "L-1" :confidence 0.9 :stake :high} st)]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (some #(= :attenuation-unusable (:rule %)) (:violations v)))
      (is (some #(= :as-of-day-unusable (:rule %)) (:violations v))))))

(deftest hard-on-restoral-over-ceiling
  (testing "the ceiling binds every link operation, not only :approve-test"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-service-restoral :effect :propose
                                    :link-id "L-1" :measured-attenuation-db 99.0
                                    :as-of-day 200 :confidence 0.9} st)]
      (is (:hard? v))
      (is (some #(= :attenuation-exceeds-ceiling (:rule %)) (:violations v))))))

(deftest hard-on-restoral-with-expired-calibration
  (let [st (fresh-store)
        v (governor/check req {} {:op :approve-service-restoral :effect :propose
                                  :link-id "L-1" :measured-attenuation-db 2.1
                                  :as-of-day 900 :confidence 0.9} st)]
    (is (:hard? v))
    (is (some #(= :calibration-expired (:rule %)) (:violations v)))))

(deftest hard-on-restoral-of-unregistered-link
  (let [st (fresh-store)
        v (governor/check req {} {:op :approve-service-restoral :effect :propose
                                  :link-id "L-ghost" :measured-attenuation-db 2.1
                                  :as-of-day 200 :confidence 0.9} st)]
    (is (:hard? v))
    (is (some #(= :unknown-link (:rule %)) (:violations v)))))

(deftest hard-on-restoral-of-foreign-link
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {}
                            {:op :approve-service-restoral :effect :propose
                             :link-id "L-1" :measured-attenuation-db 2.1
                             :as-of-day 200 :confidence 0.9} st)]
      (is (:hard? v))
      (is (some #(= :link-wrong-client (:rule %)) (:violations v))))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (test-result 2.1 200) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

;; ── vocabulary ───────────────────────────────────────────────────────────────
;;
;; Every assertion below was measured as `{:ok? true :violations []}` on the
;; pre-change tree. The governor bound `:approve-test` by name and admitted
;; every other op, so the op set was open and the README's scope sentence
;; ("cognitive work only; climbing, splicing and tower work are field
;; territory") refused nothing.

(deftest hard-on-undeclared-operation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (test-result 99.0 500) :op :reroute-backhaul) st)]
    (is (:hard? v))
    (is (some #(= :undeclared-operation (:rule %)) (:violations v)))))

(deftest hard-on-nil-operation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (test-result 2.1 200) :op nil) st)]
    (is (:hard? v))
    (is (some #(= :undeclared-operation (:rule %)) (:violations v)))))

(deftest hard-on-reserved-field-work
  (testing "reserved is reported as an authority boundary, not a vocabulary error"
    (let [st (fresh-store)]
      (doseq [op [:climb-tower :splice-fiber :cut-over-production-fiber
                  :disconnect-emergency-line]]
        (let [v (governor/check req {} (assoc (test-result 2.1 200) :op op) st)]
          (is (:hard? v) (str op " must hard-block"))
          (is (not (:escalate? v)) (str op " must never be offered to a human"))
          (is (some #(= :no-field-authority (:rule %)) (:violations v)) (str op)))))))

;; ── unusable proposed values ────────────────────────────────────────────────
;;
;; The ceiling was guarded by `(number? x)` and the window by `(integer? x)`,
;; so each HARD invariant switched itself off exactly when the field it
;; compared was absent or the wrong type. A ceiling a missing field turns off
;; is not a ceiling.

(deftest hard-on-missing-measurement
  (let [st (fresh-store)
        v (governor/check req {} (dissoc (test-result 2.1 200) :measured-attenuation-db) st)]
    (is (:hard? v))
    (is (some #(= :attenuation-unusable (:rule %)) (:violations v)))))

(deftest hard-on-non-numeric-measurement
  (let [st (fresh-store)
        v (governor/check req {} (test-result "99" 200) st)]
    (is (:hard? v))
    (is (some #(= :attenuation-unusable (:rule %)) (:violations v)))))

(deftest hard-on-negative-measurement
  (testing "a passive link cannot amplify; a negative reading undercuts any ceiling"
    (let [st (fresh-store)
          v (governor/check req {} (test-result -50.0 200) st)]
      (is (:hard? v))
      (is (some #(= :attenuation-unusable (:rule %)) (:violations v))))))

(deftest hard-on-nan-measurement
  (testing "NaN makes every inequality false, so it is never over the ceiling"
    (let [st (fresh-store)
          v (governor/check req {} (test-result ##NaN 200) st)]
      (is (:hard? v))
      (is (some #(= :attenuation-unusable (:rule %)) (:violations v))))))

(deftest hard-on-missing-day
  (let [st (fresh-store)
        v (governor/check req {} (dissoc (test-result 2.1 200) :as-of-day) st)]
    (is (:hard? v))
    (is (some #(= :as-of-day-unusable (:rule %)) (:violations v)))))

(deftest hard-on-non-integer-day
  (let [st (fresh-store)
        v (governor/check req {} (test-result 2.1 "500") st)]
    (is (:hard? v))
    (is (some #(= :as-of-day-unusable (:rule %)) (:violations v)))))

(deftest hard-on-unnamed-link
  (let [st (fresh-store)
        v (governor/check req {} (dissoc (test-result 2.1 200) :link-id) st)]
    (is (:hard? v))
    (is (some #(= :link-not-named (:rule %)) (:violations v)))))

;; ── the registered record is a value too ────────────────────────────────────

(deftest hard-on-link-registered-without-comparable-facts
  (testing "a crash is not a refusal, and on :cljs the same comparison admits"
    (let [st (fresh-store)]
      (store/register-link! st {:link-id "L-BAD" :client-id "client-1"})
      (let [v (governor/check req {} (assoc (test-result 99.0 900) :link-id "L-BAD") st)]
        (is (:hard? v))
        (is (some #(= :link-record-no-ceiling (:rule %)) (:violations v)))
        (is (some #(= :link-record-no-calibration (:rule %)) (:violations v)))))))

;; ── provenance ──────────────────────────────────────────────────────────────

(deftest hard-on-client-record-that-identifies-nobody
  (testing "the empty map registers under the key nil, and a request with no :client-id resolved to it"
    (let [st (store/mem-store)]
      (store/register-client! st {})
      (store/register-link! st {:link-id "L-1" :client-id nil
                                :max-attenuation-db 3.0 :calibration-expiry-day 400})
      (let [v (governor/check {} {} (test-result 2.1 200) st)]
        (is (:hard? v))
        (is (some #(= :client-record-unidentified (:rule %)) (:violations v)))))))

;; ── confidence ──────────────────────────────────────────────────────────────

(deftest hard-on-unusable-confidence
  (testing "a confidence that cannot be compared cannot buy its way out of escalation"
    (let [st (fresh-store)]
      (doseq [c [99.0 -1.0 "high" ##NaN]]
        (let [v (governor/check req {} (assoc (test-result 2.1 200) :confidence c) st)]
          (is (:hard? v) (str "confidence " (pr-str c)))
          (is (some #(= :confidence-unusable (:rule %)) (:violations v)) (str (pr-str c))))))))

(deftest absent-confidence-escalates-rather-than-blocking
  (testing "absence reads as 0.0, which escalates -- the safe direction, left alone"
    (let [st (fresh-store)
          v (governor/check req {} (dissoc (test-result 2.1 200) :confidence) st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

;; ── ordering ────────────────────────────────────────────────────────────────

(deftest hard-beats-escalate
  (testing "a proposal that is both hard-blocked and escalating must hold: no human may approve it"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-service-restoral :effect :propose
                                    :link-id "L-1" :measured-attenuation-db 99.0
                                    :as-of-day 200 :confidence 0.1} st)]
      (is (:hard? v))
      (is (not (:escalate? v))))))
