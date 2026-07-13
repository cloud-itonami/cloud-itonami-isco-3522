(ns telecomtech.advisor
  "TelecomEngineeringTechniciansAdvisor — proposes a link-test
  operation (approve a test, approve a service restoral) for a
  registered organization. Swappable mock/llm; the advisor ONLY
  proposes — `telecomtech.governor` checks the attenuation ceiling
  and calibration validity independently. Modeled on
  cloud-itonami-isco-4311's advisor.

  A proposal: {:op :approve-test|:approve-service-restoral
               :effect :propose :link-id str
               :measured-attenuation-db number :as-of-day int
               :stake kw :confidence n :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake link-id measured-attenuation-db as-of-day] :as request}]
  {:op op
   :effect :propose
   :link-id link-id
   :measured-attenuation-db measured-attenuation-db
   :as-of-day as-of-day
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a telecommunications engineering test advisor. Given a
   request, propose an :op, the :link-id, :measured-attenuation-db
   and :as-of-day, an honest :confidence and a :stake. Never call an
   over-attenuation test or a test from expired calibration equipment
   conforming — the governor checks both against the registered link
   record.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
