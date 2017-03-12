(ns roll.lumo-test
  (:require [roll.core-test]
            [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is testing run-tests]]
            [cljs.nodejs :as nodejs]))

(def fs (nodejs/require "fs"))

(defn -main [& argv]
  (println "Testing with lumo")

  (is (= (reader/read-string (fs.readFileSync "sample-config.edn" "utf-8"))))

  (run-tests 'roll.core-test))
