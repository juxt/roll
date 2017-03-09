(ns roll.samples
  (:require [roll.core]
            [clojure.test.check.generators :as gen]
            [clojure.spec :as s]))

(defn generate-roll-config []
  {:environment (gen/generate (s/gen :roll.core/environment))
   :releases-bucket (gen/generate (s/gen :roll.core/releases-bucket))
   :vpc-id (gen/generate (s/gen :roll.core/vpc-id))
   :subnets ["subnet1" "subnet2"]
   :kms {:root (gen/generate (s/gen string?))
         :admins [(gen/generate (s/gen string?))]}
   :common {:aws-region "eu-west-1"}

   :load-balancers {:foo-service [(-> :roll.core/load-balancer
                                      s/gen
                                      gen/generate
                                      (assoc :ssl-policy "ELBSecurityPolicy-2015-05"
                                             :certificate-arn "arn:aws:acm:eu-west-1:123456789:certificate/AAAA"))]}

   ;; Todo enforce the alias points to an actual load-balancer, would be cool, is possible?:
   :route-53-aliases [(-> (s/gen :roll.core/route-53-alias)
                          (gen/generate)
                          (assoc :load-balancer :foo-service
                                 :name-prefix "foo")
                          (dissoc :resource-name))]
   :services {:foo-service (assoc (gen/generate (s/gen :roll.core/service))
                                  :availability-zones ["eu-west-1a" "eu-west-1b"])}
   :asgs [{:service :foo-service
           :load-balancer :foo-service
           :release-artifact (gen/generate (s/gen string?))
           :version (gen/generate (s/gen string?))}]

   :bastion {:key-name "some-key"
             :user-data "some init user data for the bastion"}})
