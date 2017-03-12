(ns roll.tf
  (:require [cljs.reader :as reader]
            [cljs.nodejs :as nodejs]
            [roll.core]))

(def fs (nodejs/require "fs"))

(defn- write-tf [data output-file]
  (let [output-file (or output-file "deployment.tf.json")]
    (println "Reading" output-file)
    (fs.writeFileSync output-file data)))

(defn -main [role-file output-file]
  (let [role-file (or role-file "roll.edn")]
    (println "Reading" role-file)
    (-> role-file
        (fs.readFileSync "utf-8")
        reader/read-string
        roll.core/preprocess
        (roll.core/deployment->tf {:roll-home "node_modules/@juxt/roll"})
        roll.core/->tf-json
        (write-tf output-file))))
