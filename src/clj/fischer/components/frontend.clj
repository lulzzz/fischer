(ns fischer.components.frontend
  (:require [com.stuartsierra.component :as cp]
            [ring.middleware.json :as rjson]
            [org.httpkit.server :as server]
            [outpace.config :refer [defconfig!]]
            [ring.util.response :as resp]
            [compojure.core :as cpj]
            [clojure.tools.logging :as log]
            [taoensso.sente :as sente]
            [ring.middleware.keyword-params :as mkp]
            [ring.middleware.params :as mp]
            [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
            [clojure.core.async :as async])
  (:import (org.httpkit.server HttpServer)))

(defconfig! frontend-host)
(defconfig! frontend-port)

(let [{:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket! (get-sch-adapter) {})]

  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)                                     ; ChannelSocket's receive channel
  (def chsk-send! send-fn)                                  ; ChannelSocket's send API fn
  (def connected-uids connected-uids)                       ; Watchable, read-only atom
  )

(defn apply-to-vals [myMap myFun]
  (into {} (map (fn [[k v]] [k (if (map? v) (apply-to-vals v myFun) (myFun v))]) myMap)))

(defn model-trainer-info [model-trainer req]
  (resp/response @(:trained-profiles model-trainer)))

(defn merged-routes [handlers model-trainer]
  (->> (apply cpj/routes (conj handlers
                               (cpj/GET "/models" req (model-trainer-info model-trainer req))
                               (cpj/GET "/ws" req (ring-ajax-get-or-ws-handshake req))
                               (cpj/POST "/ws" req (ring-ajax-post req))))
       (rjson/wrap-json-response)
       mkp/wrap-keyword-params
       mp/wrap-params))

(defn handle_event [[event data]]
  (str event "///" data))

(defn start-identity-reply! []
  (async/go-loop []
    (let [msg (async/<! ch-chsk)
          ?response (handle_event (:event msg))]
      (if-let [rply-fn (:?reply-fn msg)]
        ((:?reply-fn msg) ?response))
      (recur))))

(defn start-push-predictions! [pred-buffer-ch]
  (async/go-loop []
    (let [profiles->prediction-buffer (async/<! pred-buffer-ch)
          clients (:any @connected-uids)
          network-viable-map (reduce-kv #(assoc %1 %2 (into [] %3)) {} profiles->prediction-buffer)]
      (log/info "Send new predictions to following clients: " clients)
      (doseq [uid clients]
        (chsk-send! uid [:fischer/predictions-changed network-viable-map]))
      (recur))))

(defrecord Frontend [handlers model-trainer pred-buffer-ch]
  cp/Lifecycle
  (start [self]
    (log/info "start frontend with following pre-registered handlers:")
    (log/info @handlers)
    (let [shutdown-fn (server/run-server (merged-routes @handlers model-trainer)
                                         {:host frontend-host
                                          :port frontend-port})]
      (start-identity-reply!)
      (start-push-predictions! pred-buffer-ch)
      (assoc self :shutdown-fn shutdown-fn)))
  (stop [self]
    (log/info "stop frontend...")
    ((:shutdown-fn self))
    (dissoc self :shutdown-fn)))

(defn new-frontend [handlers pred-buffer-ch]
  (map->Frontend {:handlers handlers
                  :pred-buffer-ch pred-buffer-ch}))