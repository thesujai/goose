(ns goose.integration-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [goose.worker :as w]
    [goose.client :as c]
    [goose.retry :as retry])
  (:import
    [java.util UUID]))

(def redis-url
  (let [host (or (System/getenv "GOOSE_REDIS_HOST") "localhost")
        port (or (System/getenv "GOOSE_REDIS_PORT") "6379")]
    (str "redis://" host ":" port)))

(def client-opts
  (assoc c/default-opts
    :queue "test"
    :redis-url redis-url))

(def worker-opts
  (assoc w/default-opts
    :redis-url redis-url
    :queue "test"
    :graceful-shutdown-time-sec 1
    :scheduler-polling-interval-sec 1))

; ======= TEST: Async execution ==========
(def fn-called (promise))
(defn placeholder-fn [arg]
  (deliver fn-called arg))

(deftest enqueue-dequeue-execute-test
  (testing "Goose executes a function asynchronously"
    (let [arg "async-execute-test"
          worker (w/start worker-opts)]
      (is (uuid? (UUID/fromString (c/async client-opts `placeholder-fn arg))))
      (is (= arg (deref fn-called 100 :e2e-test-timed-out)))
      (w/stop worker))))

; ======= TEST: Scheduling ==========
(def scheduled-fn-called (promise))
(defn scheduled-fn [arg]
  (deliver scheduled-fn-called arg))

(deftest scheduler-test
  (testing "Goose executes a scheduled function asynchronously"
    (let [arg "scheduling-test"
          scheduler (w/start worker-opts)]
      (c/async (assoc client-opts :schedule-opts {:perform-in-sec 1})
               `scheduled-fn arg)
      (is (= arg (deref scheduled-fn-called 1100 :scheduler-test-timed-out)))
      (w/stop scheduler))))

; ======= TEST: Error handling ==========
(defn immediate-retry [_] 1)

(def failed-on-execute (promise))
(def failed-on-1st-retry (promise))
(def succeeded-on-2nd-retry (promise))

(defn test-error-handler [_ ex]
  (if (realized? failed-on-execute)
    (deliver failed-on-1st-retry ex)
    (deliver failed-on-execute ex)))

(defn erroneous-fn [arg]
  (when-not (realized? failed-on-execute)
    (/ 1 0))
  (when-not (realized? failed-on-1st-retry)
    (throw (ex-info "error" {})))
  (deliver succeeded-on-2nd-retry arg))
(deftest retry-test
  (testing "Goose retries an errorneous function"
    (let [arg "retry-test"
          retry-queue "test-retry-queue"
          retry-opts (assoc retry/default-opts
                       :max-retries 2
                       :retry-delay-sec-fn-sym `immediate-retry
                       :retry-queue retry-queue
                       :error-handler-fn-sym `test-error-handler)
          worker (w/start worker-opts)
          retry-worker (w/start (assoc worker-opts :queue retry-queue))]
      (c/async (assoc client-opts :retry-opts retry-opts) `erroneous-fn arg)

      (is (= java.lang.ArithmeticException (type (deref failed-on-execute 100 :retry-execute-timed-out))))
      (w/stop worker)

      (is (= clojure.lang.ExceptionInfo (type (deref failed-on-1st-retry 1100 :1st-retry-timed-out))))

      (is (= arg (deref succeeded-on-2nd-retry 1100 :2nd-retry-timed-out)))
      (w/stop retry-worker))))

; ======= TEST: Retries exhausted ==========

(def job-dead (promise))
(defn test-death-handler [_ ex]
  (deliver job-dead ex))
(defn dead-fn []
  (/ 1 0))
(deftest dead-test
  (testing "Goose marks a job as dead upon reaching max retries"
    (let [retry-opts (assoc retry/default-opts
                       :max-retries 0
                       :death-handler-fn-sym `test-death-handler)
          worker (w/start worker-opts)]
      (c/async (assoc client-opts :retry-opts retry-opts) `dead-fn)
      (is (= java.lang.ArithmeticException (type (deref job-dead 100 :death-handler-timed-out))))
      (w/stop worker))))
