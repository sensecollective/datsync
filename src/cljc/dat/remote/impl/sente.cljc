(ns dat.remote.impl.sente
  #?(:cljs (:require-macros [cljs.core.async.macros :as async-macros :refer [go go-loop]]))
  (:require #?@(:clj [[clojure.core.async :as async :refer [go go-loop]]]
                :cljs [[cljs.core.async :as async]])
            [dat.sync.client :as sync]
            [dat.reactor :as reactor]
            [dat.sync.utils :as utils]
            [dat.spec.protocols :as protocols]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as log #?@(:cljs [:include-macros true])]
            [taoensso.sente :as sente]
            [taoensso.sente.packers.transit :as sente-transit]
            #?(:clj [taoensso.sente.server-adapters.http-kit :as sente-http])
            ))


;; ## Implement the comms protocols using Sente


#?(:cljs
    (do
      ;; This is a hack to get the db/fn objects to not break on data load
      (defrecord DBFn [lang params code])
      ;(defn tagged-fn [:datsync.server/db-fn])
      (cljs.reader/register-tag-parser! 'db/fn pr-str)))

(def default-sente-options
  {:packer (sente-transit/get-transit-packer)})


(defrecord SenteRemote [peers ch-recv event-chan send-fn open? sente-options
                        ;; server specific:
                        server? server-stop ring-handlers]
  component/Lifecycle
  (start [remote]
    (log/info "Starting SenteRemote Component")
    (let [chsk-route (or (:chsk-route sente-options) "/chsk")
          sente-options (merge default-sente-options (dissoc sente-options :chsk-route))
          event-chan (or event-chan (async/chan 100))
          {:as sente-fns :keys [ch-recv send-fn connected-uids
                    ajax-post-fn ajax-get-or-ws-handshake-fn]}
          (if server?
            #?(:clj (sente/make-channel-socket! sente-http/http-kit-adapter sente-options)
              :cljs (throw "Sente cannot run in server mode in cljs"))
            (sente/make-channel-socket! chsk-route sente-options))
          server-stop
          (if server?
            (sente/start-chsk-router! ch-recv (fn [event] (async/put! event-chan event)))
            ;; Set Sente to pipe it's events such that they all (at the top level) fit the standard re-frame shape. ???: this should probably be reworked to use plain maps aka segments
            (async/pipeline 1 event-chan (map (fn [x] [::event (:event x)])) ch-recv))]
      (assoc remote
        :event-chan event-chan
        :open? (atom false)
        :peers connected-uids
        :send-fn send-fn
        :ch-recv ch-recv
        :ring-handlers (when server? {:route chsk-route
                                      :get ajax-get-or-ws-handshake-fn
                                      :put ajax-post-fn}))))
  (stop [remote]
    (log/info "Stopping SenteRemote component")
    (try
      ;(when event-chan (async/close! event-chan))
      ;(when ch-recv (async/close! ch-recv))
      (log/debug "SenteRemote stopped successfully")
      (when server? (server-stop))
      (assoc remote :ch-recv nil :event-chan nil)
      (catch #?(:clj Exception :cljs :default) e
        (log/error "Error stopping SenteRemote:" e)
        remote)))
  protocols/PRemoteSendEvent
  (send-event! [{:as remote :keys [peers]} event]
     ;; TODO: send to all peers when not a client
     (if peers
       (doseq [uid (:any @peers)] (protocols/send-event! remote uid event))
       (send-fn event)))
  (send-event! [remote peer-id event]
    (send-fn peer-id event))
  protocols/PRemoteEventChan
  (remote-event-chan [remote]
    event-chan))


(defn new-sente-remote
  "Options include `:sente-options`, which is passed as the second argument to `sente/make-channel-socket!`.
  Additionally, the `:chsk-route` option of the `:sente-options` map is passed as the first argument to
  `sente/make-channel-socket!`, assuming you don't want to use the default (`\"chsk\"`). You can see all
  datsync defaults for these options in `default-sente-options`.

  Additionally, note that you can specify via the options map your own `:event-chan`, which is the channel on which
  output messages get put (the result of calling `dat.spec.protocols/remote-event-chan` on this system component)."
  ([]
   (new-sente-remote {}))
  ([options]
   (map->SenteRemote options)))


;; ## Install handler hooks; Note that the component in question here is not the remote, but the app

;; Event handlers should be created and installed as data:


(reactor/register-handler
  ::event
  (fn [app db [_ sente-message]]
    (log/debug "Sente message recieved:" (first sente-message))
    (reactor/resolve-to app db [sente-message])))

(reactor/register-handler
  :chsk/state
  ;<<<<<<< buildable
  ;(fn [app db [_ [last-state curr-state]]]
  ;  (try
  ;    (log/info "in chsk/state handler for msg:" [last-state curr-state])
  ;    (if (or (:first-open? curr-state)
  ;            (and (:ever-opened? curr-state)
  ;                 (not (:ever-opened? last-state))))
  ;      (do
  ;        (log/info "Requesting bootstrap...")
  ;        (reactor/with-effect [:dat.remote/send-event! [:dat.sync.client/bootstrap nil]]
  ;          db))
  ;      (do
  ;        (log/info "Not the first open; no bootstrap needed")
  ;        db))
  (fn [app db [_ message]]
    (try
      ;; This or conditional takes care of different versions of sente
      (if (or (-> message second :first-open?) (:first-open? message))
        (do
          (log/info "First channel socket open; Sending bootstrap message")
          ;; Note: This needs to be a more explicit part of the dat.remote protocol/spec
          (reactor/resolve-to app db
            [[:dat.remote/connected true]]))
        db)
      (catch #?(:clj Exception :cljs :default) e
        (log/error "Exception handling :chsk/state:" e)))))

(reactor/register-handler :chsk/ws-ping
  (fn [& args] true))

(reactor/register-handler
  :chsk/handshake
  (fn [app db [_ {:as ev-msg :keys [?data]}]]
    (log/warn "Calling :chsk/handshare! You should probably write something here! (reactor/register-handler :chsk/handshake (fn [app db [_ hs-msg]] ...))")
    db))

(reactor/register-handler
  :chsk/recv
  ;(fn [app db {:as ev-msg :keys [?data]}]
  (fn [app db [_ event]]
    ;; This is just to deal with how sente organizes things on it's chans; If we wanted though, we could
    ;; manually track things here
    (log/info ":chsk/recv for event-id:" (first event))
    (reactor/resolve-to app db
      [event])))


