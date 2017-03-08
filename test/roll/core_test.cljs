(ns roll.core-test
  #?(:clj (:require [roll.core]
                    [clojure.test.check.generators :as gen]
                    [clojure.spec :as s]
                    [clojure.edn :as edn]
                    [clojure.test :refer :all]
                    [clojure.java.io :as io]))
  #?(:cljs (:require [roll.core]
                     [clojure.test.check.generators :as gen]
                     [clojure.spec :as s]
                     [cljs.tools.reader :as edn]
                     [cljs.test :refer [deftest is testing]]
                     [goog.string :as gstring]
                     [goog.string.format])))

(defn- sample-roll-config []
  {:environment (gen/generate (s/gen :roll.core/environment))
   :releases-bucket (gen/generate (s/gen :roll.core/releases-bucket))
   :vpc-id (gen/generate (s/gen :roll.core/vpc-id))
   :subnets ["subnet1" "subnet2"]
   :kms {:root (gen/generate (s/gen string?))
         :admins [(gen/generate (s/gen string?))]}
   :common {:aws-region "eu-west-1"}
   :load-balancers {:foo-service [(gen/generate (s/gen :roll.core/load-balancer))]}
   ;; Todo enforce the alias points to an actual load-balancer, would be cool, is possible?:
   :route-53-aliases [(assoc (gen/generate (s/gen :roll.core/route-53-alias)) :load-balancer :foo-service)]
   :services {:foo-service (gen/generate (s/gen :roll.core/service))}

   :asgs [{:service :foo-service
           :load-balancer :foo-service
           :release-artifact (gen/generate (s/gen string?))
           :version (gen/generate (s/gen string?))}]})

(deftest test-build-sample-terraform-deployment-config
  (roll.core/deployment->tf (sample-roll-config)))
