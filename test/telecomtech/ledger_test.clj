(ns telecomtech.ledger-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [telecomtech.ledger :as led]))

(defn- three []
  (-> []
      (led/append (led/commit-entry {:link-id "L-1"} :actor))
      (led/append (led/hold-entry {:violations [{:rule :attenuation-exceeds-ceiling}]}))
      (led/append (led/commit-entry {:link-id "L-2"} :human))))

(deftest an-intact-chain-verifies
  (let [l (three)]
    (is (= {:ok? true :length 3} (led/verify l)))))

(deftest the-empty-ledger-verifies
  (is (= {:ok? true :length 0} (led/verify []))))

(deftest a-tampered-entry-breaks-the-chain
  (testing "editing content the hash covers is detected at that entry"
    (let [l (assoc-in (three) [1 :verdict] {:violations []})
          r (led/verify l)]
      (is (not (:ok? r)))
      (is (= 1 (:broken-at r)))
      (is (= :hash-mismatch (:reason r))))))

(deftest reordering-breaks-the-chain
  (let [r (led/verify (vec (reverse (three))))]
    (is (not (:ok? r)))
    (is (= :seq-mismatch (:reason r)))))

(deftest dropping-a-middle-entry-breaks-the-chain
  (let [l (three)
        r (led/verify [(nth l 0) (nth l 2)])]
    (is (not (:ok? r)))
    (is (= 1 (:broken-at r)))))

(deftest truncation-verifies-and-that-limit-is-stated
  (testing "a chain cannot detect entries it never saw; verify claims only what it can show"
    (is (:ok? (led/verify (subvec (three) 0 2))))))

(deftest the-hash-covers-position-not-just-content
  (testing "two identical entries at different positions hash differently"
    (let [l (-> [] (led/append {:x 1}) (led/append {:x 1}))]
      (is (not= (:ledger/hash (nth l 0)) (:ledger/hash (nth l 1)))))))

(deftest the-hash-is-deterministic
  (is (= (:ledger/hash (led/entry [] {:a 1}))
         (:ledger/hash (led/entry [] {:a 1}))))
  (is (not= (:ledger/hash (led/entry [] {:a 1}))
            (:ledger/hash (led/entry [] {:a 2})))))

(deftest the-hash-stays-inside-exactly-representable-integers
  (testing "so :clj and :cljs compute the same number without BigInt or 32-bit wrap"
    (doseq [e (three)]
      (is (integer? (:ledger/hash e)))
      (is (<= 0 (:ledger/hash e)))
      (is (< (:ledger/hash e) 2147483647)))))

(deftest approval-provenance-is-recorded-not-omitted
  (testing "pre-change a human-approved restoral and an automatic test were indistinguishable"
    (is (= :human (:approved-by (led/commit-entry {} :human))))
    (is (= :actor (:approved-by (led/commit-entry {} :actor))))
    (is (= :none (:approved-by (led/hold-entry {}))))
    (is (every? #(contains? % :approved-by) (three)))))

(deftest hold-entries-carry-the-reason
  (let [v {:violations [{:rule :calibration-expired}]}]
    (is (= v (:verdict (led/hold-entry v))))))

(deftest summary-names-every-entry
  (let [s (led/summary (three))]
    (is (re-find #"approved-by=actor" s))
    (is (re-find #"approved-by=human" s))
    (is (re-find #"approved-by=none" s))
    (is (= 3 (count (str/split-lines s))))))
