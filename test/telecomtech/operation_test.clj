(ns telecomtech.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [telecomtech.operation :as op]))

(deftest supported-and-reserved-are-disjoint
  (testing "an op in both maps would make the refusal reason depend on lookup order"
    (is (empty? (filter op/reserved? (keys op/supported))))
    (is (empty? (filter op/supported? (keys op/reserved))))))

(deftest every-supported-op-declares-both-properties
  (testing "a missing :link-op? reads as false, which is exactly how the restoral got exempted"
    (doseq [[o m] op/supported]
      (is (contains? m :escalates?) (str o " must declare :escalates?"))
      (is (contains? m :link-op?) (str o " must declare :link-op?"))
      (is (string? (:summary m)) (str o " must carry a summary")))))

(deftest every-reserved-op-says-why
  (doseq [[o m] op/reserved]
    (is (string? (:reason m)) (str o " must say why it is reserved"))
    (is (seq (:reason m)) (str o " reason must not be blank"))))

(deftest restoral-binds-to-a-link
  (testing "the single field whose absence exempted the restoral from three HARD invariants"
    (is (op/link-op? :approve-service-restoral))
    (is (op/link-op? :approve-test))))

(deftest restoral-escalates
  (is (op/escalates? :approve-service-restoral))
  (is (op/escalates? :flag-fault))
  (is (not (op/escalates? :approve-test))))

(deftest non-link-operations-bind-nothing
  (is (not (op/link-op? :draft-test-plan)))
  (is (not (op/link-op? :flag-fault))))

(deftest undeclared-ops-answer-false-everywhere
  (testing "these predicates never admit; the governor hard-blocks before consulting them"
    (doseq [o [:reroute-backhaul :climb-tower nil "approve-test" 42]]
      (is (not (op/supported? o)) (str (pr-str o)))
      (is (not (op/escalates? o)) (str (pr-str o)))
      (is (not (op/link-op? o)) (str (pr-str o))))))

(deftest declared-covers-both-maps
  (is (op/declared? :approve-test))
  (is (op/declared? :climb-tower))
  (is (not (op/declared? :reroute-backhaul)))
  (is (not (op/declared? nil))))

(deftest field-work-is-reserved-not-merely-absent
  (testing "the README's scope sentence, in a form that can refuse"
    (doseq [o [:climb-tower :splice-fiber :cut-over-production-fiber
               :disconnect-emergency-line]]
      (is (op/reserved? o) (str o))
      (is (string? (op/reserved-reason o)) (str o)))))
