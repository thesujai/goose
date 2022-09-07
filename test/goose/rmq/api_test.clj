(ns goose.rmq.api-test
  (:require
    [goose.api.dead-jobs :as dead-jobs]
    [goose.api.enqueued-jobs :as enqueued-jobs]
    [goose.client :as c]
    [goose.retry :as retry]
    [goose.test-utils :as tu]
    [goose.worker :as w]

    [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
    [java.util UUID]))

; ======= Setup & Teardown ==========
(use-fixtures :each tu/rmq-fixture)

(deftest enqueued-jobs-test
  (testing "[rmq] enqueued-jobs API"
    (c/perform-async tu/rmq-client-opts `tu/my-fn)
    (is (= 1 (enqueued-jobs/size tu/client-rmq-broker tu/queue)))
    (enqueued-jobs/purge tu/client-rmq-broker tu/queue)
    (is (= 0 (enqueued-jobs/size tu/client-rmq-broker tu/queue)))))

(defn death-handler [_ _ _])
(def dead-fn-atom (atom 0))
(defn dead-fn [id]
  (swap! dead-fn-atom inc)
  (throw (Exception. (str id " died!"))))

(deftest dead-jobs-test
  (testing "[rmq] dead-jobs API"
    (let [worker (w/start (assoc tu/rmq-worker-opts :threads 1))
          retry-opts (assoc retry/default-opts
                       :max-retries 0
                       :death-handler-fn-sym `death-handler)
          job-opts (assoc tu/rmq-client-opts :retry-opts retry-opts)
          _ (doseq [id (range 2)] (c/perform-async job-opts `dead-fn id))
          circuit-breaker (atom 0)]
      ; Wait until 2 jobs have died after execution.
      (while (and (> 2 @circuit-breaker) (not= 2 @dead-fn-atom))
        (swap! circuit-breaker inc)
        (Thread/sleep 40))
      (w/stop worker)
      (is (= 2 (dead-jobs/size tu/client-rmq-broker)))
      (is (uuid? (UUID/fromString (:id (dead-jobs/pop tu/client-rmq-broker)))))
      (is (true? (dead-jobs/purge tu/client-rmq-broker)))
      (is (= 0 (dead-jobs/size tu/client-rmq-broker))))))