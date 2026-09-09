(ns telecomtech.sim-test
  (:require [clojure.test :refer [deftest is testing]]
            [telecomtech.phase :as phase]
            [telecomtech.sim :as sim]))

(deftest the-scenario-table-passes
  (let [r (sim/run)]
    (is (:ok? r) (sim/report r))
    (is (empty? (:mismatches r)))
    (is (empty? (:wrote-anyway r)))
    (is (empty? (:ledger-breaks r)))))

(deftest the-table-demonstrates-refusals
  (testing "a governed actor that refuses nothing has shown nothing"
    (is (pos? (:refusals (sim/run))))))

(deftest the-table-also-demonstrates-a-commit
  (testing "a table that only refuses proves the governor says no, not that it works"
    (let [r (sim/run)]
      (is (some #(= :commit (:actual %)) (:results r))))))

(deftest the-table-demonstrates-an-escalation
  (let [r (sim/run)]
    (is (some #(= :request-approval (:actual %)) (:results r)))))

(deftest no-scenario-failed-to-reach-a-phase
  (testing ":no-phase means the run never reached :decide -- not a verdict"
    (is (not-any? #(= :no-phase (:actual %)) (:results (sim/run))))))

(deftest every-scenario-says-why
  (doseq [s sim/scenarios]
    (is (keyword? (:name s)))
    (is (contains? phase/phases (:expect s)) (str (:name s) " must expect a real phase"))
    (is (string? (:why s)) (str (:name s) " must say why"))
    (is (seq (:why s)) (str (:name s) " reason must not be blank"))))

(deftest scenario-names-are-unique
  (testing "duplicates would silently overwrite each other's checkpoint thread"
    (let [ns- (map :name sim/scenarios)]
      (is (= (count ns-) (count (set ns-)))))))

(deftest the-run-is-deterministic
  (testing "same table, same phases -- no clock, no randomness, no shared state"
    (is (= (mapv (juxt :name :actual) (:results (sim/run)))
           (mapv (juxt :name :actual) (:results (sim/run)))))))

(deftest report-refuses-to-report-a-pass-with-no-refusals
  (testing "the harness's own failure mode: a table that stopped exercising the governor"
    (let [s (sim/report {:results [] :refusals 0 :mismatches [] :wrote-anyway []
                         :ledger-breaks [] :ok? true})]
      (is (re-find #"REFUSING TO REPORT A PASS" s))
      (is (not (re-find #"PASS\n$" s))))))

(deftest report-says-fail-when-a-scenario-mismatches
  (let [s (sim/report {:results [] :refusals 3 :mismatches [{:name :x}]
                       :wrote-anyway [] :ledger-breaks [] :ok? false})]
    (is (re-find #"FAIL" s))))
