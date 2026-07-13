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
  (let [st (fresh-store)
        v (governor/check req {} {:op :approve-service-restoral :effect :propose
                                  :link-id "L-1" :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (test-result 2.1 200) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
