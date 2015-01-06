(ns watchservice.core-test
  (:require [clojure.test :refer :all]
            [watchservice.core :refer :all]
            [clojure.java.io :as io]
            [clojure.core.async :as async]
            [me.raynes.fs :refer [list-dir mkdir delete-dir delete file chdir create with-mutable-cwd]]))

(def tmp "/Volumes/RamDiskCache/tmp")

(let [_ (delete-dir tmp)
      _ (mkdir tmp)
      _ (spit (str tmp "/file0.txt") "content...")]

  (deftest test-watch-dir
    (testing "create file + delete"

      (let [[ws c wsc] (make-watcher [tmp])
            file1-path (str tmp "/file1.txt")]

        (try

          ;(Thread/sleep 2000)

          (spit file1-path "content...")
          ;(Thread/sleep 2000)
          (is (= [:create file1-path] (async/<!! c)))

          (delete file1-path)
          ;(Thread/sleep 2000)
          (is (= [:delete file1-path] (async/<!! c)))

          (finally
            (stop-watcher ws)))))))
