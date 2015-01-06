(ns watchservice.core
  (:import
    [java.nio.file FileSystems Path Paths StandardWatchEventKinds]
    [com.barbarysoftware.watchservice StandardWatchEventKind WatchableFile]
    (java.util.concurrent TimeUnit))
  (:require
    [clojure.java.io :as io]
    [clojure.core.async :as async]))

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

(defn- register-recursive
  [service path events]
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

(defn- start-service
  [paths]
  (let [service (new-watch-service)
        doreg #(register-recursive %1 %2 [:create :modify :delete])]
    (doseq [path paths] (doreg service (io/file path)))
    (let [c (async/chan)
          service-channel (async/thread
                            (loop []
                              (async/<!! (async/timeout 300))
                              (let [watch-key (poll-watch-key service 20 TimeUnit/MILLISECONDS)]
                                (if watch-key
                                  (if-not (.isValid watch-key)
                                    (do (println "invalid watch key %s\n" (.watchable watch-key))
                                        (recur))
                                    (do (doseq [event (.pollEvents watch-key)]
                                          (let [dir (.toFile (.watchable watch-key))
                                                changed (io/file dir (str (.context event)))
                                                etype (enum->kw service (.kind event))
                                                dir? (.isDirectory changed)]
                                            (cond
                                              (and dir? (= :create etype)) (doreg service changed)
                                              (not dir?) (do (println "file-event: " etype " : " changed) (async/>!! c [etype (.getPath changed)])))))
                                        (and watch-key (.reset watch-key) (recur))))
                                  (recur)))))]
      [service c service-channel])))

(def ^:private watchers (atom {}))

(defn stop-watcher
  [k]
  (when-let [w (@watchers k)]
    (println "stopping watcher: " w)
    (.close w)))

(defn make-watcher
  [paths]
  (let [ws (str (gensym))
        [service c wsc] (start-service paths)
        _ (println "started watcher: " ws)
        _ (println "watch-channel: " c)
        fs (->> paths (mapcat (comp file-seq io/file)) (filter (memfn isFile)))]
    (swap! watchers assoc ws service)
    (doseq [f fs] (do (println "initial files: " (.getPath f))))
    [ws c wsc]))
