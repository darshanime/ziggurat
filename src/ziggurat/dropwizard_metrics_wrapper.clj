(ns ziggurat.dropwizard-metrics-wrapper
  (:require [clojure.tools.logging :as log]
            [ziggurat.config :refer [ziggurat-config]]
            [clojure.walk :refer [stringify-keys]])
  (:import [com.gojek.metrics.datadog.transport UdpTransport UdpTransport$Builder]
           [io.dropwizard.metrics5 MetricRegistry]
           [com.gojek.metrics.datadog DatadogReporter]
           [java.util.concurrent TimeUnit]
           [io.dropwizard.metrics5 Histogram Meter MetricName MetricRegistry]))

(defonce metrics-registry
  (MetricRegistry.))

(def reporter-and-transport-state (atom nil))

(defn initialize [statsd-config]
  (let [{:keys [enabled host port]} statsd-config]
    (when enabled
      (let [transport (-> (UdpTransport$Builder.)
                          (.withStatsdHost host)
                          (.withPort port)
                          (.build))

            reporter  (-> (DatadogReporter/forRegistry metrics-registry)
                          (.withTransport transport)
                          (.build))]
        (log/info "Starting statsd reporter")
        (.start reporter 1 TimeUnit/SECONDS)
        (reset! reporter-and-transport-state {:reporter reporter :transport transport})))))

(defn terminate []
  (when-let [{:keys [reporter transport]} @reporter-and-transport-state]
    (.stop ^DatadogReporter reporter)
    (.close ^UdpTransport transport)
    (reset! reporter-and-transport-state nil)
    (log/info "Stopped statsd reporter")))

(defn- get-tagged-metric
  [metric-name tags]
  (.tagged ^MetricName metric-name tags))

(defn mk-meter
  ([category metric]
   (mk-meter category metric {}))
  ([category metric tags]
   (let [namespace        (str category "." metric)
         metric-name      (MetricRegistry/name ^String namespace nil)
         stringified-tags (stringify-keys tags)
         tagged-metric    (get-tagged-metric metric-name stringified-tags)]
     (.meter ^MetricRegistry metrics-registry ^MetricName tagged-metric))))

(defn mk-histogram
  ([category metric]
   (mk-histogram category metric {}))
  ([category metric tags]
   (let [namespace        (str category "." metric)
         metric-name      (MetricRegistry/name ^String namespace nil)
         stringified-tags (stringify-keys tags)
         tagged-metric    (.tagged ^MetricName metric-name stringified-tags)]
     (.histogram ^MetricRegistry metrics-registry ^MetricName tagged-metric))))

(defn update-counter
  [namespace metric tags sign value]
  (let [meter (mk-meter namespace metric tags)]
    (.mark ^Meter meter (sign value))))

(defn update-histogram
  [namespace tags value]
  (let [histogram (mk-histogram namespace "all" tags)]
    (.update ^Histogram histogram value)))
