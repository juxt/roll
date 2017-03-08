(ns roll.lumo-test
  (:require [roll.core-test]
            [cljs.test :refer-macros [deftest is testing run-tests]]))

(defn -main [& argv]
  (println "Testing with lumo")
  (run-tests 'roll.core-test))
