(ns watchservice.core
  (:import
    [java.nio.file FileSystems Path Paths StandardWatchEventKinds]
    [com.barbarysoftware.watchservice StandardWatchEventKind WatchableFile]
    (java.util.concurrent TimeUnit))
  (:require
    [clojure.java.io :as io]
    [clojure.core.async :as async]))

(defn println- [& more]
  (.write *out* (str (clojure.string/join " " more) "\n"))
  (flush))

(def ^:private watchers (atom {}))

(defprotocol IRegister
  (register [this path events])
  (enum->kw [this x]))

(extend-type java.nio.file.WatchService
  IRegister
  (register [service path events]
    (let [path (Paths/get (str path) (into-array String []))
          events (into-array (map {:create StandardWatchEventKinds/ENTRY_CREATE
                                   :modify StandardWatchEventKinds/ENTRY_MODIFY
                                   :delete StandardWatchEventKinds/ENTRY_DELETE}
                                  events))]
      (.register path service events)))
  (enum->kw [this x]
    (-> {StandardWatchEventKinds/ENTRY_CREATE :create
         StandardWatchEventKinds/ENTRY_MODIFY :modify
         StandardWatchEventKinds/ENTRY_DELETE :delete}
        (get x))))

(extend-type com.barbarysoftware.watchservice.WatchService
  IRegister
  (register [service path events]
    (let [path (WatchableFile. (io/file path))
          events (into-array (map {:create StandardWatchEventKind/ENTRY_CREATE
                                   :modify StandardWatchEventKind/ENTRY_MODIFY
                                   :delete StandardWatchEventKind/ENTRY_DELETE}
                                  events))]
      (.register path service events)))
  (enum->kw [this x]
    (-> {StandardWatchEventKind/ENTRY_CREATE :create
         StandardWatchEventKind/ENTRY_MODIFY :modify
         StandardWatchEventKind/ENTRY_DELETE :delete}
        (get x))))

(defn- register-recursive [service path events]
  (register service path events)
  (doseq [dir (.listFiles (io/file path))]
    (when (.isDirectory dir)
      (register-recursive service dir events))))

(defn- new-watch-service []
  (if (= "Mac OS X" (System/getProperty "os.name"))
    ;; Use barbarywatchservice library for Mac OS X so we can avoid polling.
    (com.barbarysoftware.watchservice.WatchService/newWatchService)

    ;; Use java.nio file system watcher
    (.newWatchService (FileSystems/getDefault))))

(defn- take-watch-key [service]
  (try
    (.take service)
    (catch java.nio.file.ClosedWatchServiceException _ nil)
    (catch com.barbarysoftware.watchservice.ClosedWatchServiceException _ nil)))

(defn- poll-watch-key [service & opts]
  (try
    (if-let [[timeout unit] opts]
      (.poll service timeout unit)
      (.poll service))
    (catch java.nio.file.ClosedWatchServiceException _ nil)
    (catch com.barbarysoftware.watchservice.ClosedWatchServiceException _ nil)))

(defn- start-service-go [paths f recursive]
  (let [service (new-watch-service)
        events [:create :modify :delete]
        doreg (if recursive #(register-recursive %1 %2 events) #(register %1 %2 events))
        ctrl-chan (async/chan)]

    (doseq [path paths] (doreg service (io/file path))) ; todo store watch-keys

    (async/go-loop [cmd-old :run cmd-new :run]
      (let [chg-state (not (= cmd-old cmd-new))]
        (condp = cmd-new

          ; todo cmd for cancelling watch-keys

          :run (do
                 (when chg-state
                   (println- "SWITCH " cmd-old cmd-new))
                 (println- "running...")

                 (let [watch-key (poll-watch-key service 20 TimeUnit/MILLISECONDS)]
                   (when watch-key
                     (if-not (.isValid watch-key)
                       (do (println- "invalid watch key %s\n" (.watchable watch-key))) ; todo
                       (do (doseq [event (.pollEvents watch-key)]
                             (let [dir (.toFile (.watchable watch-key))
                                   changed (io/file dir (str (.context event)))
                                   etype (enum->kw service (.kind event))
                                   dir? (.isDirectory changed)]
                               (cond
                                 (and dir? (= :create etype)) (doreg service changed) ; todo
                                 (not dir?) (do
                                              (println- "file-event: " etype " : " changed)
                                              (f etype (.getPath changed))))))
                           (if (not (.reset watch-key)) (println- "error"))))))

                 (let [[cmd _] (async/alts! [ctrl-chan (async/timeout 1000)])]
                   (recur :run (if (nil? cmd) :run cmd))))

          :suspend (do
                     (when chg-state
                       (println- "SWITCH " cmd-old cmd-new))
                     (println- "suspended...")

                     (let [[cmd _] (async/alts! [ctrl-chan (async/timeout Integer/MAX_VALUE)])]
                       (recur :suspend (if (nil? cmd) :suspend cmd))))

          :stop (println- "stopped")

          (do
            (println- "invalid command")
            (recur cmd-old cmd-old)))))

    [service ctrl-chan]))

(defn stop-watcher [k]
  (when-let [w (@watchers k)]
    (println- "CLOSING " k)
    (.close w)))

(defn make-watcher-go [paths f & {:keys [recursive list-all] :or {recursive nil list-all nil}}]
  (let [service-id (str (gensym "watchservice"))
        [service ctrl-chan] (start-service-go paths f recursive)]
    (swap! watchers assoc service-id service)
    (if list-all
      [(mapcat (comp file-seq io/file) paths) service-id ctrl-chan]
      [service-id ctrl-chan])))
