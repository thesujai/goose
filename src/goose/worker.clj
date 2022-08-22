(ns goose.worker
  (:require
    [goose.brokers.broker :as b]
    [goose.defaults :as d]
    [goose.statsd :as statsd]))

(defprotocol Shutdown
  "Shutdown a worker object."
  (stop [_]))

(def default-opts
  "Default config for Goose worker."
  {:threads               1
   :queue                 d/default-queue
   :graceful-shutdown-sec 30
   :middlewares           nil
   :error-service-cfg     nil
   :statsd-opts           statsd/default-opts})

(defn start
  "Starts a threadpool for worker."
  [{:keys [broker statsd-opts]
    :as   opts}]
  (statsd/initialize statsd-opts)
  (let [shutdown-fn (b/start broker opts)]
    (reify Shutdown
      (stop [_] (shutdown-fn)))))
