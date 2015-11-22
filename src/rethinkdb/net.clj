(ns rethinkdb.net
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [rethinkdb.query-builder :refer [parse-query]]
            [rethinkdb.types :as types]
            [rethinkdb.response :refer [parse-response]]
            [rethinkdb.utils :refer [str->bytes int->bytes bytes->int pp-bytes]])
  (:import [java.io Closeable InputStream OutputStream DataInputStream]))

(declare send-continue-query send-stop-query)

(defn close
  "Clojure proxy for java.io.Closeable's close."
  [^Closeable x]
  (.close x))

(deftype Cursor [conn token coll]
  Closeable
  (close [this] (and (send-stop-query conn token) :closed))
  clojure.lang.Seqable
  (seq [this] (do
                (Thread/sleep 250)
                (lazy-seq (concat coll (send-continue-query conn token))))))

(defn send-int [^OutputStream out i n]
  (.write out (int->bytes i n) 0 n))

(defn send-str [^OutputStream out s]
  (let [n (count s)]
    (.write out (str->bytes s) 0 n)))

(defn read-str [^DataInputStream in n]
  (let [resp (byte-array n)]
    (.readFully in resp 0 n)
    (String. resp)))

(defn ^String read-init-response [^InputStream in]
  (let [resp (byte-array 4096)]
    (.read in resp 0 4096)
    (clojure.string/replace (String. resp) #"\W*$" "")))


(defn read-response* [^InputStream in]
  (let [recvd-token (byte-array 8)
        length (byte-array 4)]
    (.read in recvd-token 0 8)
    (.read in length 0 4)
    (let [recvd-token (bytes->int recvd-token 8)
          length (bytes->int length 4)
          json (read-str in length)]
      [recvd-token json])))

(defn write-query [out [token json]]
  (send-int out token Long/BYTES)
  (send-int out (count json) Integer/BYTES)
  (send-str out json))

(defn make-connection-loops [in out]
  (let [recv-chan (async/chan)
        send-chan (async/chan)
        pub       (async/pub recv-chan first)
        ;; Receive loop
        recv-loop (async/go-loop []
                    (when (try
                            (let [resp (read-response* in)]
                              (log/trace "Received raw response %s" resp)
                              (async/>! recv-chan resp))
                            (catch java.net.SocketException e
                              false))
                      (recur)))
        ;; Send loop
        send-loop (async/go-loop []
                    (when-let [query (async/<! send-chan)]
                      (log/trace "Sending raw query %s")
                      (write-query out query)
                      (recur)))]
    ;; Return as map to merge into connection
    {:pub pub
     :loops [recv-loop send-loop]
     :r-ch recv-chan
     :ch send-chan}))

(defn close-connection-loops
  [conn]
  (let [{:keys [pub ch r-ch] [recv-loop send-loop] :loops} @conn]
    (async/unsub-all pub)
    ;; Close send channel and wait for loop to complete
    (async/close! ch)
    (async/<!! send-loop)
    ;; Close recv channel
    (async/close! r-ch)))

(defn send-query* [conn token query]
  (let [chan (async/chan)
        {:keys [pub ch]} @conn]
    (async/sub pub token chan)
    (async/>!! ch [token query])
    (let [[recvd-token json] (async/<!! chan)]
      (assert (= recvd-token token) "Must not receive response with different token")
      (async/unsub-all pub token)
      (json/parse-string-strict json true))))

(defn send-query [conn token query]
  (let [{:keys [db]} @conn
        query (if (and db (= 2 (count query))) ;; If there's only 1 element in query then this is a continue or stop query.
                ;; TODO: Could provide other global optargs too
                (concat query [{:db [(types/tt->int :DB) [db]]}])
                query)
        json (json/generate-string query)
        {type :t resp :r :as json-resp} (send-query* conn token json)
        resp (parse-response resp)]
    (condp get type
      #{1 5} (first resp) ;; Success Atom, Query returned a single RQL datatype
      #{2} (do ;; Success Sequence, Query returned a sequence of RQL datatypes.
             (swap! (:conn conn) update-in [:waiting] #(disj % token))
             resp)
      #{3} (if (get (:waiting @conn) token) ;; Success Partial, Query returned a partial sequence of RQL datatypes
               (lazy-seq (concat resp (send-continue-query conn token)))
               (do
                 (swap! (:conn conn) update-in [:waiting] #(conj % token))
                 (Cursor. conn token resp)))
      (let [ex (ex-info (str "RethinkDB server: " (first resp)) json-resp)]
        (log/error ex)
        (throw ex)))))

(defn send-start-query [conn token query]
  (log/debugf "Sending start query with token %d, query: %s" token query)
  (send-query conn token (parse-query :START query)))

(defn send-continue-query [conn token]
  (log/debugf "Sending continue query with token %d" token)
  (send-query conn token (parse-query :CONTINUE)))

(defn send-stop-query [conn token]
  (log/debugf "Sending stop query with token %d" token)
  (send-query conn token (parse-query :STOP)))

(defn send-server-query [conn token]
  (log/debugf "Sending server query with token %d" token)
  (send-query conn token (parse-query :SERVER_INFO)))
