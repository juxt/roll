(ns roll.generate-config-sample
  (:require [roll.samples]
            [cljs.nodejs :as nodejs]))

(def fs (nodejs/require "fs"))

(defn- spit [f data]
  (fs.writeFileSync f data))

(defn -main [& argv]
  (spit "sample-config.edn" (roll.samples/generate-roll-config)))
