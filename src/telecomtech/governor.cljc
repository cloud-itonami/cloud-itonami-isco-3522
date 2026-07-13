(ns telecomtech.governor
  "TelecomEngineeringTechniciansGovernor — the independent safety/
  traceability layer for the ISCO-08 3522 community telecommunications
  engineering technicians actor (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.governor. Test-technician
  twist: a measured attenuation is arithmetic comparison against the
  registered ceiling, and the test's as-of day must fall within the
  registered calibration validity window — a test result from expired
  calibration equipment is not evidence, it's a guess.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. link basis           — a test approval must cite a REGISTERED
                           link belonging to this client.
    4. attenuation ceiling — the proposed measured-attenuation-db must
                           not exceed the link's registered
                           :max-attenuation-db (arithmetic, not
                           eyeballed).
    5. calibration validity — the proposed as-of day must not exceed
                           the link's registered
                           :calibration-expiry-day (expired
                           calibration equipment produces a guess, not
                           evidence).
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :approve-service-restoral (bringing a previously-failed
                           link back into production carriage).
    7. low confidence (< `confidence-floor`)."
  (:require [telecomtech.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record l]
  (let [{:keys [op measured-attenuation-db as-of-day]} proposal
        approve? (= :approve-test op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and approve? (nil? l))
      (conj {:rule :unknown-link :detail "未登録 link への試験承認は不可"})

      (and approve? l (not= (:client-id l) (:client-id request)))
      (conj {:rule :link-wrong-client :detail "link が別 client のもの"})

      (and approve? l (number? measured-attenuation-db)
           (> measured-attenuation-db (:max-attenuation-db l)))
      (conj {:rule :attenuation-exceeds-ceiling
             :detail (str "測定減衰量 " measured-attenuation-db "dB > 登録済み上限 "
                          (:max-attenuation-db l) "dB（減衰量は測定であって目視ではない）")})

      (and approve? l (integer? as-of-day) (> as-of-day (:calibration-expiry-day l)))
      (conj {:rule :calibration-expired
             :detail (str "day " as-of-day " > 校正有効期限 " (:calibration-expiry-day l)
                          "（失効校正機器の測定結果は証拠ではなく推測）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `telecomtech.store/Store`. Pure — never mutates
  the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        l (some->> (:link-id proposal) (store/link store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record l)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :approve-service-restoral (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
