(ns roll.utils
  (:require [lumo.io]
            [cljstache.core :refer [render]]
            [clojure.string]))

(defn resolve-path [path]
  (clojure.string/join "_"  (map name path)))

(defn ->snake [s]
  (clojure.string/replace (name s) "-" "_"))

(defn tf-path-to [path]
  (->> path
       (map ->snake)
       (clojure.string/join ".")))

(defn $ [path]
  (str "${" (tf-path-to path) "}"))

(defn render-mustache [m t]
  (let [t (lumo.io/slurp (lumo.io/resource t))]
    (render t m)))

(defn ->json
  [m]
  (js/JSON.stringify (clj->js m) nil 4))
