(ns roll.core-test
  #?(:clj (:require [roll.core]
                    [clojure.edn :as edn]
                    [clojure.test :refer :all]
                    [clojure.java.io :as io]))
  #?(:cljs (:require [roll.core]
                     [cljs.tools.reader :as edn]
                     [cljs.test :refer [deftest is testing]]
                     [goog.string :as gstring]
                     [goog.string.format])))

(deftest test-hello-world
  (is (= true true)))
