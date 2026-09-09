(ns telecomtech.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [telecomtech.actor :as actor]
            [telecomtech.ledger :as ledger]
            [telecomtech.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-link! st {:link-id "L-1" :client-id "client-1"
                              :name "backbone-run-12"
                              :max-attenuation-db 3.0
                              :calibration-expiry-day 400})
    st))

(deftest commits-an-in-spec-current-calibration-test
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-test :stake :low
                 :link-id "L-1" :measured-attenuation-db 2.1 :as-of-day 200}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-over-attenuation-test
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-test :stake :low
                 :link-id "L-1" :measured-attenuation-db 9.0 :as-of-day 200}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-restores-service-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        ;; The measurement and the day used to be absent here, and the test
        ;; asserted that this escalated cleanly. That was the defect written
        ;; down as the contract -- a restoral is the operation whose premise
        ;; is that the link already failed, and it reached a human carrying no
        ;; evidence at all. `telecomtech.governor` now hard-blocks that shape;
        ;; see governor-test/hard-on-restoral-without-evidence.
        request {:client-id "client-1" :op :approve-service-restoral :stake :high
                 :link-id "L-1" :measured-attenuation-db 2.1 :as-of-day 200}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))

;; ── what the wired graph writes, not only what `check` returns ──────────────

(deftest the-ledger-the-graph-leaves-behind-is-chained-and-verifies
  (testing "the store held a plain vector; any prefix or permutation of it was indistinguishable"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})]
      (actor/run-request! graph {:client-id "client-1" :op :approve-test :link-id "L-1"
                                 :measured-attenuation-db 2.1 :as-of-day 200} {} "t-a")
      (actor/run-request! graph {:client-id "client-1" :op :approve-test :link-id "L-1"
                                 :measured-attenuation-db 9.0 :as-of-day 200} {} "t-b")
      (let [l (store/ledger st)]
        (is (= 2 (count l)))
        (is (:ok? (ledger/verify l)))
        (is (= [0 1] (mapv :ledger/seq l)))
        (is (not (:ok? (ledger/verify (vec (reverse l))))))))))

(deftest a-commit-records-who-approved-it
  (testing "pre-change a human-approved restoral and an automatic test were the same shape"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})]
      (actor/run-request! graph {:client-id "client-1" :op :approve-test :link-id "L-1"
                                 :measured-attenuation-db 2.1 :as-of-day 200} {} "t-auto")
      (is (= [:actor] (mapv :approved-by (store/ledger st)))))
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})]
      (actor/run-request! graph {:client-id "client-1" :op :approve-service-restoral
                                 :link-id "L-1" :measured-attenuation-db 2.1
                                 :as-of-day 200} {} "t-human")
      (is (empty? (store/ledger st)) "the interrupt must not write")
      (actor/approve! graph "t-human")
      (is (= [:human] (mapv :approved-by (store/ledger st)))))))

(deftest a-hold-is-recorded-with-its-reason
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})]
    (actor/run-request! graph {:client-id "client-1" :op :approve-test :link-id "L-1"
                               :measured-attenuation-db 9.0 :as-of-day 200} {} "t-hold")
    (let [e (first (store/ledger st))]
      (is (= :hold (:disposition e)))
      (is (= :none (:approved-by e)))
      (is (some #(= :attenuation-exceeds-ceiling (:rule %)) (:violations (:verdict e)))))))

(deftest the-graph-holds-a-reserved-operation-and-writes-nothing
  (testing "field work reaches the graph and is refused there, not only in `check`"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})
          result (actor/run-request! graph {:client-id "client-1" :op :climb-tower} {} "t-climb")]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/records-of st "client-1"))))))

(deftest the-graph-holds-a-restoral-that-breaches-the-ceiling
  (testing "the operation that used to interrupt with an empty violation list"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})
          result (actor/run-request! graph {:client-id "client-1"
                                            :op :approve-service-restoral :link-id "L-1"
                                            :measured-attenuation-db 99.0
                                            :as-of-day 200} {} "t-bad-restoral")]
      (is (= :hold (:disposition (:state result))))
      (is (not= :interrupted (:status result)))
      (is (empty? (store/records-of st "client-1"))))))
