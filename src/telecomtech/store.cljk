(ns telecomtech.store
  "SSoT for the ISCO-08 3522 community telecommunications engineering
  technicians actor (itonami actor pattern, ADR-2607011000 /
  CLAUDE.md Actors section). Modeled on cloud-itonami-isco-4311's
  bookkeeping.store.

  Domain:

    client — a registered organization (:client-id, :name)
    link   — a registered cable/fiber link under test {:link-id
             :client-id :name :max-attenuation-db number
             :calibration-expiry-day int}. `:max-attenuation-db` is
             the registered maximum acceptable insertion loss a
             proposed test result must not exceed; test equipment
             calibration is only trustworthy through
             `:calibration-expiry-day` (simple monotonic day clock,
             day 0 = epoch for this link) — a test result from expired
             calibration equipment is not evidence, it's a guess.
    record — a committed operating record (approved test result) —
             written ONLY via commit-record!.
    ledger — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (link [s link-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-link! [s l])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (link [_ link-id] (get-in @a [:links link-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-link! [s l]
    (swap! a assoc-in [:links (:link-id l)] l) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :links {} :records [] :ledger []}
                                   seed)))))
