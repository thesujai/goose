(ns goose.job
  (:require
    [goose.utils :as u]
    [goose.redis :as r]
    [goose.scheduler :as scheduler]))

(defn- internal-opts
  [queue]
  {:redis-fn r/enqueue-back
   :queue    queue})

(defn- prefix-retry-queue-if-present
  [opts]
  (if-let [queue (:retry-queue opts)]
    (assoc opts :retry-queue (u/prefix-queue queue))
    opts))

(defn new
  [{:keys [queue schedule-opts retry-opts]}
   execute-fn-sym args]
  (let [prefixed-queue (u/prefix-queue queue)
        job {:id             (str (random-uuid))
             :queue          prefixed-queue
             :execute-fn-sym execute-fn-sym
             :args           args
             :retry-opts     (prefix-retry-queue-if-present retry-opts)
             :enqueued-at    (u/epoch-time-ms)
             :internal-opts  (internal-opts prefixed-queue)}]
    (scheduler/update-job-if-scheduled job schedule-opts)))

(defn push
  [redis-conn
   {{:keys [redis-fn queue run-at] :as internal-opts} :internal-opts
    :as                                               internal-job}]
  (let [job (dissoc internal-job :internal-opts)]
    (cond
      run-at (redis-fn redis-conn queue run-at job)
      :else (redis-fn redis-conn queue job))
    (:id job)))
