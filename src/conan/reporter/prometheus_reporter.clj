(ns conan.reporter.prometheus-reporter
  (:require [com.stuartsierra.component :as cp]
            [de.otto.goo.goo :as goo]
            [conan.reporter.prediction-reporter :as r]
            [compojure.core :as cpj]
            [ring.util.response :as resp]))

(defonce ad_gauge (goo/register-gauge! :conan/anomaly-detected {:labels [:profile]}))
(defonce scr_gauge (goo/register-gauge! :conan/score {:labels [:profile]}))
(defonce eps_gauge (goo/register-gauge! :conan/epsylon {:labels [:profile]}))

(defrecord PrometheusReporter []
  r/PredictionReporter
  (report [_ profile->prediction]
    (let [update-profile-gauge (fn [[profile-name prediction]]
                                 (goo/observe! :conan/anomaly-detected {:profile (name profile-name)} (:p prediction))
                                 (goo/observe! :conan/score {:profile (name profile-name)} (:s prediction))
                                 (goo/observe! :conan/epsylon {:profile (name profile-name)} (:e prediction)))]
      (run! update-profile-gauge profile->prediction))))

(defn prom-handler [endpoint-path]
  (cpj/GET endpoint-path req (fn [req] (resp/response (goo/text-format)))))

(defn new-prometheus-reporter! [handlers-atom endpoint-path]
  (swap! handlers-atom conj (prom-handler endpoint-path)))



