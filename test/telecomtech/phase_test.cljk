(ns telecomtech.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [telecomtech.phase :as phase]))

(deftest hard-outranks-escalate
  (testing "escalating a hard block would ask a human to approve what no human may approve"
    (is (= :hold (phase/of-verdict {:hard? true :escalate? true})))
    (is (= :hold (phase/of-verdict {:hard? true})))
    (is (= :request-approval (phase/of-verdict {:escalate? true})))
    (is (= :commit (phase/of-verdict {})))
    (is (= :commit (phase/of-verdict {:hard? false :escalate? false})))))

(deftest only-commit-writes
  (is (phase/writes? :commit))
  (is (not (phase/writes? :hold)))
  (is (not (phase/writes? :request-approval))))

(deftest both-non-writing-phases-are-refusals
  (testing "request-approval is a refusal to act without a human, not an approval-in-waiting"
    (is (phase/refusal? :hold))
    (is (phase/refusal? :request-approval))
    (is (not (phase/refusal? :commit)))))

(deftest only-request-approval-needs-a-human
  (is (phase/human-required? :request-approval))
  (is (not (phase/human-required? :hold)))
  (is (not (phase/human-required? :commit))))

(deftest request-approval-is-not-terminal
  (testing "it resumes into :commit; hold and commit end the run"
    (is (not (phase/terminal? :request-approval)))
    (is (phase/terminal? :hold))
    (is (phase/terminal? :commit))))

(deftest approved-commit-identifies-the-human-path
  (is (phase/approved-commit? :request-approval))
  (is (not (phase/approved-commit? :commit)))
  (is (not (phase/approved-commit? nil))))

(deftest unknown-phases-never-admit-a-write
  (testing "a typo in a phase keyword must not read as permission to write"
    (is (not (phase/writes? :approve)))
    (is (not (phase/writes? nil)))
    (is (phase/refusal? :typo))))
