(defproject com.apa512/rethinkdb "0.12.0-SNAPSHOT"
  :description "RethinkDB client"
  :url "http://github.com/apa512/clj-rethinkdb"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :codox {:defaults {:doc/format :markdown}
          :src-dir-uri "https://github.com/apa512/clj-rethinkdb/blob/master/"
          :src-linenum-anchor-prefix "L"}
  :global-vars {*warn-on-reflection* true}
  :plugins [[codox "0.8.13"]]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.48" :scope "provided"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/tools.logging "0.3.1"]
                 [cheshire "5.5.0"]
                 [rethinkdb-protobuf "2.2.0"]
                 [com.google.protobuf/protobuf-java "2.6.1"]
                 [clj-time "0.10.0"]]
  :profiles {:dev {:resource-paths ["test-resources"]
                   :dependencies [[ch.qos.logback/logback-classic "1.1.3"]]}}
  :jvm-opts ["-Xmx512m"]
  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]])
