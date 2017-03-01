;; Copyright © 2017, JUXT LTD.

(defproject roll "0.0.4"
  :description "Rolling Terraform releases orchestrated with ClojureScript."
  :url "http://github.com/juxt/roll"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :resource-paths ["tf"]
  :source-paths ["src"]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.8.0"]]}}
  :plugins [[org.clojars.ride_on/lein-sha-version "0.3.0"]]
  :sha {:manifest-header "Git-Sha"
        :write-version? false})
