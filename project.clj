(defproject watchservice "0.1.0-SNAPSHOT"
            :description "FIXME: write description"
            :url "http://example.com/FIXME"
            :license {:name "Eclipse Public License"
                      :url  "http://www.eclipse.org/legal/epl-v10.html"}
            :dependencies [[org.clojure/clojure "1.7.0-alpha5"]
                           [me.raynes/fs "1.4.6"]
                           [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                           [net.java.dev.jna/jna "4.1.0"]
                           [de.clojj/mac-watchservice "1.0-SNAPSHOT"]]
            ;:aot :all
            ;:java-source-paths ["../third_party/barbarywatchservice/src"]
            ; :main ^:skip-aot watchservice.core
            :target-path "target/%s"
            :profiles {:uberjar {:aot :all}})
