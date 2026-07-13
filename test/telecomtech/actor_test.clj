(ns telecomtech.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [telecomtech.actor :as actor]
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
        request {:client-id "client-1" :op :approve-service-restoral :stake :high
                 :link-id "L-1"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
