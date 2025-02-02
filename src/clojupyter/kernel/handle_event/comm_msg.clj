(ns clojupyter.kernel.handle-event.comm-msg
  (:require
   [clojupyter.kernel.comm-global-state :as comm-global-state]
   [clojupyter.kernel.comm-atom :as ca]
   [clojupyter.kernel.handle-event.ops :refer [definterceptor s*set-response]]
   [clojupyter.kernel.jup-channels :as jup]
   [clojupyter.log :as log]
   [clojupyter.messages :as msgs]
   [clojupyter.messages-specs :as msp]
   [clojupyter.state :as state]
   [clojupyter.util-actions :as u!]
   [clojure.pprint :as pp]
   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :refer [instrument]]
   [io.simplect.compose :refer [def- c C p P >->> >>->]]
   [io.simplect.compose.action :as a :refer [action step side-effect]]))

;;; ------------------------------------------------------------------------------------------------------------------------
;;; MISC INTERNAL
;;; ------------------------------------------------------------------------------------------------------------------------

(def- IOPUB :iopub_port)
(def NO-OP-ACTION
  (action (step `[list] {:op :no-op})))

(def comm-msg?*
  (memoize
   (fn [msgtype]
     (contains? #{msgs/COMM-CLOSE msgs/COMM-INFO-REPLY msgs/COMM-INFO-REQUEST msgs/COMM-MSG msgs/COMM-OPEN}
                msgtype))))

(defn- return
  ([ctx state]
   (return ctx NO-OP-ACTION state state))
  ([ctx action state]
   (return ctx action state state))
  ([ctx action old-state new-state]
   (return ctx action old-state new-state {}))
  ([ctx action old-state new-state extra-map]
   (if (identical? old-state new-state)
     (log/debug "COMM calc return unchanged -"
                (log/ppstr (merge {:ctx ctx, :state old-state}
                                  {:action (if (= action NO-OP-ACTION) :none action)}
                                  extra-map)))
     (log/debug "COMM calc return - " (log/ppstr (merge {:ctx ctx, :action (if (= action NO-OP-ACTION) :none action)
                                                         :old-state old-state, :new-state new-state}
                                                        extra-map))))
   [action new-state]))

(defn- jupmsg-spec
  ([port msgtype content]
   (jupmsg-spec port msgtype nil content))
  ([port msgtype metadata content]
   (merge {:op :send-jupmsg, :port port, :msgtype msgtype, :content content}
          (when metadata
            {:metadata metadata}))))

(defmulti ^:private calc*
  (fn [msgtype _ _] msgtype))

(defmethod calc* :default
  [msgtype state ctx]
  (throw (ex-info (str "Unhandled message type: " msgtype)
           {:msgtype msgtype, :state state, :ctx ctx})))

;;; ------------------------------------------------------------------------------------------------------------------------
;;; COMM MESSAGES - HANDLED PER `:method` field
;;; ------------------------------------------------------------------------------------------------------------------------

(defmulti handle-comm-msg
  "The `:method` field of COMM messages determines what needs to happen."
  (fn [method _ _] method))

(defmethod handle-comm-msg :default
  [method S ctx]
  (let [msgstr (str "HANDLE-COMM-MSG - bad method: '" method "'.")
        data {:S S, :ctx ctx}]
    (log/error msgstr data)
    (throw (ex-info msgstr data))))

(defn handle-comm-msg-unknown
  [ctx S comm_id]
  (log/info (str "COMM - unknown comm-id: " comm_id))
  (return ctx S))

(defmethod handle-comm-msg msgs/COMM-MSG-REQUEST-STATE
  [_ S {:keys [req-message jup] :as ctx}]
  (assert (and req-message jup))
  (log/debug "received COMM:REQUEST-STATE")
  (let [method (msgs/message-comm-method req-message)
        comm-id (msgs/message-comm-id req-message)
        present? (comm-global-state/known-comm-id? S comm-id)]
    (assert method)
    (assert comm-id)
    (assert (= method msgs/COMM-MSG-REQUEST-STATE))
    (if present?
      (let [msgtype msgs/COMM-MSG
            comm-atom (comm-global-state/comm-atom-get S comm-id)
            target-name (ca/target comm-atom)
            msg-metadata ca/MESSAGE-METADATA
            content (msgs/update-comm-msg comm-id msgs/COMM-MSG-UPDATE target-name @comm-atom)
            A (action (step [`jup/send!! jup IOPUB req-message msgtype msg-metadata content]
                            (jupmsg-spec IOPUB msgtype msg-metadata content)))]
        (return ctx A S))
      (handle-comm-msg-unknown ctx S comm-id))))

(defmethod handle-comm-msg msgs/COMM-MSG-UPDATE
  [_ S {:keys [req-message] :as ctx}]
  (assert req-message)
  (log/debug "received COMM:UPDATE")
  (let [{{:keys [comm_id] {:keys [method state]} :data} :content} req-message]
    (assert comm_id)
    (assert state)
    (if-let [comm-atom (comm-global-state/comm-atom-get S comm_id)]
      (let [A (action (side-effect #(ca/state-update! comm-atom state)
                                   {:op :update-agent :comm-id comm_id :new-state state}))]
        (return ctx A S))
      (handle-comm-msg-unknown ctx S comm_id))))

(defmethod calc* msgs/COMM-MSG
  [_ S {:keys [req-message] :as ctx}]
  (assert req-message)
  (log/debug "received COMM:MSG")
  (let [{{:keys [comm_id] {:keys [method]} :data} :content} req-message]
    (assert comm_id)
    (assert method)
    (log/debug "received COMM:MSG: " comm_id ":" method)
    (try
      (handle-comm-msg method S ctx)
      (catch RuntimeException e
        (log/debug "default handler failed" comm_id ":" method (class e))
        (if-let [comm-atom (comm-global-state/comm-atom-get S comm_id)]
          (do
            (log/debug @comm-atom)
            (log/debug "Comm method: " method)
            (handle-comm-msg-unknown ctx S comm_id)))))))

;;; ------------------------------------------------------------------------------------------------------------------------
;;; COMM-OPEN, COMM-CLOSE
;;; ------------------------------------------------------------------------------------------------------------------------

(defmethod calc* msgs/COMM-OPEN
  [_ S {:keys [req-message jup] :as ctx}]
  (assert (and req-message jup ctx))
  (log/debug "received COMM:OPEN")
  (let [{{:keys [comm_id target_module target_name]
          {:keys [state buffer_paths] :as data} :data :as content} :content}
        ,, req-message]
    (assert S)
    (assert (s/valid? ::msp/target_name target_name))
    (assert (s/valid? ::msp/target_module target_module))
    (assert (string? comm_id))
    (let [present? (comm-global-state/known-comm-id? S comm_id)
          state (or state {})
          buffer_paths (or buffer_paths [])]
    (if present?
        (do (log/debug "COMM-OPEN - already present")
            (return ctx S))
        (let [msgtype msgs/COMM-OPEN
              content (msgs/comm-open-content comm_id data {:target_module target_module :target_name target_name})
              comm-atom (ca/create jup req-message target_name comm_id state)
              A (action (step [`jup/send!! jup IOPUB req-message msgtype content]
                              (jupmsg-spec IOPUB msgtype content)))
              S' (comm-global-state/comm-atom-add S comm_id comm-atom)]
          (return ctx A S S'))))))

(defmethod calc* msgs/COMM-CLOSE
  [_ S {:keys [req-message jup] :as ctx}]
  (assert (and req-message jup ctx))
  (let [{{:keys [comm_id data]} :content} req-message]
    (assert S)
    (assert (map? data))
    (assert (string? comm_id))
    (if (comm-global-state/known-comm-id? S comm_id)
      (let [msgtype msgs/COMM-CLOSE
            content (msgs/comm-close-content comm_id {})
            A (action (step [`jup/send!! jup IOPUB req-message msgtype content]
                            (jupmsg-spec IOPUB msgtype content))
                      )
            S' (comm-global-state/comm-atom-remove S comm_id)]
        (return ctx A S S'))
      (return ctx S))))

;;; ------------------------------------------------------------------------------------------------------------------------
;;; COMM-INFO-REQUEST
;;; ------------------------------------------------------------------------------------------------------------------------

(defmethod calc* msgs/COMM-INFO-REQUEST
  [_ S {:keys [req-message req-port jup] :as ctx}]
  (assert (and req-message req-port jup ctx))
  (let [msgtype msgs/COMM-INFO-REPLY
        content (msgs/comm-info-reply-content (->> (for [comm-id (comm-global-state/known-comm-ids S)]
                                                     [comm-id (ca/target (comm-global-state/comm-atom-get S comm-id))])
                                                   (into {})))
        A (action (step [`jup/send!! jup req-port req-message msgtype content]
                        (jupmsg-spec req-port msgtype content)))]
    (return ctx A S)))

;; COMM-INFO-REPLY is never received
;; If it were to happen the message would fail in the call to `calc*`

(defn calc
  [& args]
  ;; `spec` & `instrument` seem to struggle with (redefinitions of) multi-methods
  ;; Circumvent using plain fn
  (apply calc* args))

(s/fdef calc
  :args (s/cat :msgtype comm-msg?*
               :handler-state comm-global-state/comm-state?
               :ctx (s/and map? (P get :req-message) (P get :jup)))
  :ret (s/and vector?
              (C count (p = 2))
              (C first a/action?)
              (C second comm-global-state/comm-state?)))
(instrument `calc)

;;; ------------------------------------------------------------------------------------------------------------------------
;;; EXTERNAL
;;; ------------------------------------------------------------------------------------------------------------------------

(def comm-msg? comm-msg?*)

(defn handle-message
  "Handles `req-message` and returns `Action-State` 2-tuple (first element is Action, second is
  State)."
  [state {:keys [req-message] :as ctx}]
  (let [msgtype (msgs/message-msg-type req-message)]
    (log/debug "handling message: " msgtype "with req-message:" req-message)
    (calc msgtype state ctx )))
