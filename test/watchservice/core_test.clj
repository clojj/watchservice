(ns watchservice.core-test
  (:require [clojure.test :refer :all]
            [watchservice.core :refer :all]
            [clojure.java.io :as io]
            [clojure.core.async :as async]
            [me.raynes.fs :refer [list-dir mkdir delete-dir delete file chdir create with-mutable-cwd]]))

(def tmp "/Volumes/RamDiskCache/tmp")
(def tmp-sub1 "/Volumes/RamDiskCache/tmp/sub1")

(let [_ (delete-dir tmp)
      _ (mkdir tmp)
      _ (mkdir tmp-sub1)
      _ (spit (str tmp "/file0.txt") "content...")]

  (deftest test-list-initially

    (testing "list"

      (let [[files service-id ctrl-chan] (make-watcher-go [tmp] #(println %1 " " %2) :list-all true)]

        (is (= (map #(.getPath %) files) [tmp (str tmp "/file0.txt") tmp-sub1]))

        (async/>!! ctrl-chan :stop)
        (stop-watcher service-id))))


  (deftest test-watch-dir-go

    (testing "fs events with go-loop"

      (let [file1 (str tmp "/file1.txt")
            c (async/chan 10)
            [service-id ctrl-chan] (make-watcher-go [tmp] #(do
                                                              (println %1 " " %2)
                                                              (async/>!! c %1)))]

        (try
          (Thread/sleep 2000)
          (spit file1 "content...")
          (Thread/sleep 1000)
          (is (= (async/<!! c) :create))
          (async/>!! ctrl-chan :suspend)
          (delete file1)
          (Thread/sleep 5000)
          (async/>!! ctrl-chan :run)
          (is (= (async/<!! c) :delete))
          (Thread/sleep 5000)
          (async/>!! ctrl-chan :stop)
          (finally
            (stop-watcher service-id))))))

  (deftest test-watch-dir-go-recurse

    (testing "fs events with go-loop recursively"

      (let [file1 (str tmp-sub1 "/file1-in-sub1.txt")
            [service-id ctrl-chan] (make-watcher-go [tmp] #(println %1 " " %2) :recursive true)]

        (try
          (Thread/sleep 2000)
          (spit file1 "content...")
          (Thread/sleep 1000)
          (async/>!! ctrl-chan :suspend)
          (delete file1)
          (Thread/sleep 5000)
          (async/>!! ctrl-chan :run)
          (Thread/sleep 5000)
          (async/>!! ctrl-chan :stop)
          (finally
            (stop-watcher service-id)))))))

;(deftest test-watch-dir
;  (testing "create file + delete"
;
;    (let [[ws c wsc] (make-watcher [tmp])
;          file1-path (str tmp "/file1.txt")]
;
;      (try
;
;        ;(Thread/sleep 2000)
;
;        (spit file1-path "content...")
;        ;(Thread/sleep 2000)
;        (is (= [:create file1-path] (async/<!! c)))
;
;        (delete file1-path)
;        ;(Thread/sleep 2000)
;        (is (= [:delete file1-path] (async/<!! c)))
;
;        (finally
;          (stop-watcher ws))))))

