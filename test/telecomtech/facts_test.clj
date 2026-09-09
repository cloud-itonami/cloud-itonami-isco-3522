(ns telecomtech.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [telecomtech.facts :as facts]))

(deftest usable-attenuation-rejects-what-cannot-be-compared
  (testing "each of these was measured as ADMITTED on the pre-change tree"
    (is (facts/usable-attenuation? 0))
    (is (facts/usable-attenuation? 2.1))
    (is (not (facts/usable-attenuation? nil)))
    (is (not (facts/usable-attenuation? "99")))
    (is (not (facts/usable-attenuation? -50.0)))
    (is (not (facts/usable-attenuation? ##NaN)))
    (is (not (facts/usable-attenuation? ##Inf)))
    (is (not (facts/usable-attenuation? ##-Inf)))))

(deftest nan-is-rejected-because-it-defeats-every-inequality
  (testing "NaN is under any ceiling and inside any window, simultaneously"
    (is (not (> ##NaN 3.0)))
    (is (not (facts/usable-attenuation? ##NaN)))))

(deftest usable-day-requires-a-non-negative-integer
  (is (facts/usable-day? 0))
  (is (facts/usable-day? 400))
  (is (not (facts/usable-day? nil)))
  (is (not (facts/usable-day? "500")))
  (is (not (facts/usable-day? -1)))
  (is (not (facts/usable-day? 200.5))))

(deftest usable-confidence-is-a-closed-interval
  (is (facts/usable-confidence? 0))
  (is (facts/usable-confidence? 1))
  (is (facts/usable-confidence? 0.6))
  (is (not (facts/usable-confidence? 99.0)))
  (is (not (facts/usable-confidence? -0.1)))
  (is (not (facts/usable-confidence? "high")))
  (is (not (facts/usable-confidence? ##NaN))))

(deftest provenance-separates-existence-from-identity
  (testing "nil? asks what the store returned; provenance asks whether it is a client"
    (is (= [:no-client] (mapv :rule (facts/provenance-violations {:client-id "C1"} nil))))
    (is (= [:client-record-unidentified]
           (mapv :rule (facts/provenance-violations {} {}))))
    (is (= [:client-record-unidentified]
           (mapv :rule (facts/provenance-violations {:client-id "C1"} {:client-id "   "}))))
    (is (= [:client-record-mismatch]
           (mapv :rule (facts/provenance-violations {:client-id "C2"} {:client-id "C1"}))))
    (is (empty? (facts/provenance-violations {:client-id "C1"} {:client-id "C1"})))))

(deftest vocabulary-distinguishes-undeclared-from-reserved
  (testing "conflating them would let a future edit supported-list a reserved op by accident"
    (is (= [:undeclared-operation] (mapv :rule (facts/vocabulary-violations {:op :reroute-backhaul}))))
    (is (= [:undeclared-operation] (mapv :rule (facts/vocabulary-violations {:op nil}))))
    (is (= [:no-field-authority] (mapv :rule (facts/vocabulary-violations {:op :climb-tower}))))
    (is (empty? (facts/vocabulary-violations {:op :approve-test})))))

(deftest link-binding-required-only-for-link-operations
  (testing "a draft binds nothing, so it needs no measurement"
    (is (empty? (facts/link-binding-violations {:op :draft-test-plan})))
    (is (empty? (facts/link-binding-violations {:op :flag-fault})))))

(deftest link-binding-reports-each-absence-separately
  (let [rules (set (mapv :rule (facts/link-binding-violations {:op :approve-test})))]
    (is (contains? rules :link-not-named))
    (is (contains? rules :as-of-day-unusable))
    (is (contains? rules :attenuation-unusable))))

(deftest link-binding-applies-to-the-restoral-too
  (testing "the operation that used to be exempt from all three"
    (let [rules (set (mapv :rule (facts/link-binding-violations
                                  {:op :approve-service-restoral :link-id "L-1"})))]
      (is (contains? rules :as-of-day-unusable))
      (is (contains? rules :attenuation-unusable))
      (is (not (contains? rules :link-not-named))))))

(deftest blank-link-id-counts-as-unnamed
  (is (= [:link-not-named]
         (mapv :rule (facts/link-binding-violations
                      {:op :approve-test :link-id "  " :as-of-day 1
                       :measured-attenuation-db 1.0})))))

(deftest registered-record-must-itself-be-comparable
  (testing "a registration defect is reported as one, not as a crash or an admission"
    (let [rules (set (mapv :rule (facts/link-record-violations :approve-test {:link-id "L-BAD"})))]
      (is (contains? rules :link-record-no-ceiling))
      (is (contains? rules :link-record-no-calibration)))
    (is (empty? (facts/link-record-violations
                 :approve-test {:link-id "L-1" :max-attenuation-db 3.0
                                :calibration-expiry-day 400})))))

(deftest absent-link-record-is-not-this-checks-business
  (testing "an unregistered link is :unknown-link in the governor, reported once not twice"
    (is (empty? (facts/link-record-violations :approve-test nil)))))

(deftest confidence-absence-is-not-a-violation
  (testing "absent reads as 0.0 and escalates -- the safe direction"
    (is (empty? (facts/confidence-violations {})))
    (is (empty? (facts/confidence-violations {:confidence 0.9})))
    (is (= [:confidence-unusable] (mapv :rule (facts/confidence-violations {:confidence 99.0}))))
    (is (= [:confidence-unusable] (mapv :rule (facts/confidence-violations {:confidence "high"}))))))
