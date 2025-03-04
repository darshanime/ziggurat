(ns ziggurat.mapper-test
  (:require [clojure.test :refer :all]
            [schema.core :as s]
            [ziggurat.config :refer [ziggurat-config]]
            [ziggurat.fixtures :as fix]
            [ziggurat.mapper :refer :all]
            [ziggurat.messaging.connection :refer [connection]]
            [ziggurat.metrics :as metrics]
            [ziggurat.util.rabbitmq :as rmq]
            [ziggurat.message-payload :as mp]
            [langohr.basic :as lb]
            [ziggurat.new-relic :as nr]
            [ziggurat.util.error :refer [report-error]])
  (:import (org.apache.kafka.common.header.internals RecordHeader RecordHeaders)))

(use-fixtures :once (join-fixtures [fix/init-rabbit-mq
                                    fix/silence-logging
                                    fix/mount-metrics]))

(deftest ^:integration mapper-func-test
  (let [service-name                    (:app-name (ziggurat-config))
        stream-routes                   {:default {:handler-fn #(constantly nil)}}
        topic-entity                    (name (first (keys stream-routes)))
        metadata                        {:meta "data"}
        message-payload                 {:message {:foo "bar"} :topic-entity (keyword topic-entity) :metadata metadata}
        expected-additional-tags        {:topic_name topic-entity}
        expected-metric-namespace       "message-processing"
        report-time-namespace           "handler-fn-execution-time"
        expected-metric-namespaces      [topic-entity expected-metric-namespace]
        expected-report-time-namespaces [topic-entity report-time-namespace]]
    (testing "message process should be successful"
      (let [successfully-processed?     (atom false)
            successfully-reported-time? (atom false)
            expected-metric             "success"]
        (with-redefs [metrics/increment-count  (fn [metric-namespaces metric additional-tags]
                                                 (when (and (or (= metric-namespaces expected-metric-namespaces)
                                                                (= metric-namespaces [expected-metric-namespace]))
                                                            (= metric expected-metric)
                                                            (= additional-tags expected-additional-tags))
                                                   (reset! successfully-processed? true)))
                      metrics/report-histogram (fn [metric-namespaces _ _]
                                                 (when (or (= metric-namespaces expected-report-time-namespaces)
                                                           (= metric-namespaces [report-time-namespace]))
                                                   (reset! successfully-reported-time? true)))]
          ((mapper-func (constantly :success) []) message-payload)
          (is @successfully-processed?)
          (is @successfully-reported-time?))))

    (testing "message process should successfully push to channel queue"
      (fix/with-queues (assoc-in stream-routes [:default :channel-1] (constantly :success))
        (let [successfully-processed? (atom false)
              expected-metric         "success"]
          (with-redefs [metrics/increment-count (fn [metric-namespace metric additional-tags]
                                                  (when (and (or (= metric-namespace [service-name topic-entity expected-metric-namespace])
                                                                 (= metric-namespace [expected-metric-namespace]))
                                                             (= metric expected-metric)
                                                             (= additional-tags expected-additional-tags))
                                                    (reset! successfully-processed? true)))]
            ((mapper-func (constantly :channel-1) [:channel-1]) message-payload)
            (let [message-from-mq (rmq/get-message-from-channel-instant-queue topic-entity :channel-1)]
              (is (= (-> message-payload
                         (dissoc :headers)) message-from-mq))
              (is @successfully-processed?))))))

    (testing "message process should raise exception if channel not in list"
      (fix/with-queues
        (assoc-in stream-routes [:default :channel-1] (constantly :success))
        (with-redefs [report-error (fn [e _]
                                     (let [err (Throwable->map e)]
                                       (is (= (:cause err) "Invalid mapper return code"))
                                       (is (= (-> err :data :code) :channel-1))))]
          ((mapper-func (constantly :channel-1) [:some-other-channel]) message-payload)
          (let [message-from-mq (rmq/get-message-from-channel-instant-queue topic-entity :channel-1)]
            (is (nil? message-from-mq))))))

    (testing "message process should be unsuccessful and retry"
      (fix/with-queues stream-routes
        (let [expected-message          (-> message-payload
                                            (assoc :retry-count (dec (:count (:retry (ziggurat-config))))))
              unsuccessfully-processed? (atom false)
              expected-metric           "retry"]

          (with-redefs [metrics/increment-count (fn [metric-namespace metric additional-tags]
                                                  (when (and (or (= metric-namespace [service-name topic-entity expected-metric-namespace])
                                                                 (= metric-namespace [expected-metric-namespace]))
                                                             (= metric expected-metric)
                                                             (= additional-tags expected-additional-tags))
                                                    (reset! unsuccessfully-processed? true)))]
            ((mapper-func (constantly :retry) []) message-payload)
            (let [message-from-mq (rmq/get-msg-from-delay-queue topic-entity)]
              (is (= message-from-mq expected-message)))
            (is @unsuccessfully-processed?)))))

    (testing "message process should be unsuccessful and be pushed to dlq"
      (fix/with-queues stream-routes
        (let [unsuccessfully-processed? (atom false)
              expected-metric           "dead-letter"]

          (with-redefs [metrics/increment-count (fn [metric-namespace metric additional-tags]
                                                  (when (and (or (= metric-namespace [service-name topic-entity expected-metric-namespace])
                                                                 (= metric-namespace [expected-metric-namespace]))
                                                             (= metric expected-metric)
                                                             (= additional-tags expected-additional-tags))
                                                    (reset! unsuccessfully-processed? true)))]
            ((mapper-func (constantly :dead-letter) []) message-payload)
            (let [message-from-mq (rmq/get-msg-from-dead-queue topic-entity)]
              (is (= message-payload message-from-mq)))
            (is @unsuccessfully-processed?)))))

    (testing "reports error, publishes message to retry queue if mapper-fn raises exception"
      (fix/with-queues stream-routes
        (let [expected-message          (-> message-payload
                                            (assoc :retry-count (dec (:count (:retry (ziggurat-config))))))
              report-fn-called?  (atom false)
              unsuccessfully-processed? (atom false)
              expected-metric           "failure"]
          (with-redefs [report-error (fn [_ _] (reset! report-fn-called? true))
                        metrics/increment-count (fn [metric-namespace metric additional-tags]
                                                  (when (and (or (= metric-namespace [service-name topic-entity expected-metric-namespace])
                                                                 (= metric-namespace [expected-metric-namespace]))
                                                             (= metric expected-metric)
                                                             (= additional-tags expected-additional-tags))
                                                    (reset! unsuccessfully-processed? true)))]
            ((mapper-func (fn [_] (throw (Exception. "test exception"))) []) message-payload)
            (let [message-from-mq (rmq/get-msg-from-delay-queue topic-entity)]
              (is (= message-from-mq expected-message)))
            (is @unsuccessfully-processed?)
            (is @report-fn-called?)))))

    (testing "reports execution time with topic prefix"
      (let [reported-execution-time?   (atom false)
            expected-metric-namespace  "handler-fn-execution-time"
            expected-metric-namespaces [service-name "default" expected-metric-namespace]]
        (with-redefs [metrics/report-histogram (fn [metric-namespaces _ _]
                                                 (when (or (= metric-namespaces expected-metric-namespaces)
                                                           (= metric-namespaces [expected-metric-namespace]))
                                                   (reset! reported-execution-time? true)))]
          ((mapper-func (constantly :success) []) message-payload)
          (is @reported-execution-time?))))
    (testing "User Handler function should have access to the proto message and metadata via :message and :metadata keywords"
      (let [user-handler-called (atom false)
            headers         (RecordHeaders. (list (RecordHeader. "key" (byte-array (map byte "value")))))
            user-handler-fn (fn [user-msg-payload]
                              (reset! user-handler-called true)
                              (is (= (-> message-payload
                                         (dissoc :headers)
                                         (dissoc :topic-entity)) user-msg-payload))
                              (is (some? (:message user-msg-payload)))
                              (is (some? (:metadata user-msg-payload)))
                              (is (nil?  (:retry-count user-msg-payload)))
                              (is (nil?  (:topic-entity user-msg-payload)))
                              (is (nil?  (:headers user-msg-payload))))]
        (with-redefs [metrics/increment-count  (constantly nil)
                      metrics/report-histogram (constantly nil)]
          ((mapper-func user-handler-fn []) (assoc message-payload :headers headers))
          (is @user-handler-called))))))

(deftest ^:integration channel-mapper-func-test
  (let [channel                             :channel-1
        channel-name                        (name channel)
        service-name                        (:app-name (ziggurat-config))
        stream-routes                       {:default {:handler-fn #(constantly nil)
                                                       channel     #(constantly nil)}}
        topic                               (first (keys stream-routes))
        metadata                            {:meta "data"}
        message-payload                     {:message      {:foo "bar"}
                                             :retry-count  (:count (:retry (ziggurat-config)))
                                             :topic-entity topic
                                             :metadata     metadata}
        expected-topic-entity-name          (name topic)
        expected-additional-tags            {:topic_name expected-topic-entity-name :channel_name channel-name}
        increment-count-namespace           "message-processing"
        expected-increment-count-namespaces [service-name topic channel-name increment-count-namespace]]
    (testing "message process should be successful"
      (let [successfully-processed? (atom false)
            expected-metric         "success"]
        (with-redefs [metrics/increment-count (fn [metric-namespace metric additional-tags]
                                                (when (and (or (= metric-namespace expected-increment-count-namespaces)
                                                               (= metric-namespace [increment-count-namespace]))
                                                           (= metric expected-metric)
                                                           (= additional-tags expected-additional-tags))
                                                  (reset! successfully-processed? true)))]
          ((channel-mapper-func (constantly :success) channel) message-payload)
          (is @successfully-processed?))))

    (testing "message process should be unsuccessful and retry"
      (fix/with-queues stream-routes
        (let [expected-message          (-> message-payload
                                            (assoc :retry-count (dec (:count (:retry (ziggurat-config))))))
              unsuccessfully-processed? (atom false)
              expected-metric           "retry"]

          (with-redefs [metrics/increment-count (fn [metric-namespace metric additional-tags]
                                                  (when (and (or (= metric-namespace expected-increment-count-namespaces)
                                                                 (= metric-namespace [increment-count-namespace]))
                                                             (= metric expected-metric)
                                                             (= additional-tags expected-additional-tags))
                                                    (reset! unsuccessfully-processed? true)))]
            ((channel-mapper-func (constantly :retry) channel) message-payload)
            (let [message-from-mq (rmq/get-message-from-channel-delay-queue topic channel)]
              (is (= message-from-mq expected-message)))
            (is @unsuccessfully-processed?)))))

    (testing "message should be published to dead-letter if handler returns :dead-letter keyword"
      (fix/with-queues stream-routes
        (let [unsuccessfully-processed? (atom false)
              expected-metric           "dead-letter"]

          (with-redefs [metrics/increment-count (fn [metric-namespace metric additional-tags]
                                                  (when (and (or (= metric-namespace expected-increment-count-namespaces)
                                                                 (= metric-namespace [increment-count-namespace]))
                                                             (= metric expected-metric)
                                                             (= additional-tags expected-additional-tags))
                                                    (reset! unsuccessfully-processed? true)))]
            ((channel-mapper-func (constantly :dead-letter) channel) message-payload)
            (let [message-from-mq (rmq/get-msg-from-channel-dead-queue topic channel)]
              (is (= message-from-mq message-payload)))
            (is @unsuccessfully-processed?)))))

    (testing "message should raise exception and report the error"
      (fix/with-queues stream-routes
        (let [expected-message          (-> message-payload
                                            (assoc :retry-count (dec (:count (:retry (ziggurat-config))))))
              report-fn-called?  (atom false)
              unsuccessfully-processed? (atom false)
              expected-metric           "failure"]
          (with-redefs [report-error (fn [_ _] (reset! report-fn-called? true))
                        metrics/increment-count (fn [metric-namespace metric additional-tags]
                                                  (when (and (or (= metric-namespace expected-increment-count-namespaces)
                                                                 (= metric-namespace [increment-count-namespace]))
                                                             (= metric expected-metric)
                                                             (= additional-tags expected-additional-tags))
                                                    (reset! unsuccessfully-processed? true)))]
            ((channel-mapper-func (fn [_] (throw (Exception. "test exception"))) channel) message-payload)
            (let [message-from-mq (rmq/get-message-from-channel-delay-queue topic channel)]
              (is (= message-from-mq expected-message)))
            (is @unsuccessfully-processed?)
            (is @report-fn-called?)))))

    (testing "reports execution time with topic prefix"
      (let [reported-execution-time?           (atom false)
            execution-time-namespace           "execution-time"
            expected-execution-time-namespaces [service-name expected-topic-entity-name channel-name execution-time-namespace]]
        (with-redefs [metrics/report-histogram (fn [metric-namespaces _ _]
                                                 (when (or (= metric-namespaces expected-execution-time-namespaces)
                                                           (= metric-namespaces [execution-time-namespace]))
                                                   (reset! reported-execution-time? true)))]
          ((channel-mapper-func (constantly :success) channel) message-payload)
          (is @reported-execution-time?))))
    (testing "[Channels] User Handler function should have access to the proto message and metadata via :message, :retry-count and :metadata keywords"
      (let [user-handler-called (atom false)
            user-handler-fn (fn [user-msg-payload]
                              (reset! user-handler-called true)
                              (is (= (-> message-payload
                                         (dissoc :retry-count)
                                         (dissoc :headers)
                                         (dissoc :topic-entity)) user-msg-payload))
                              (is (some? (:message user-msg-payload)))
                              (is (some? (:metadata user-msg-payload)))
                              (is (nil? (:retry-count user-msg-payload)))
                              (is (nil?  (:topic-entity user-msg-payload))))]
        (with-redefs [metrics/increment-count  (constantly nil)
                      metrics/report-histogram (constantly nil)]
          ((channel-mapper-func user-handler-fn channel) message-payload)
          (is @user-handler-called))))))
