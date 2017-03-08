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

;; TODO document Mach integration, and how other TF files in same dir can be added
;; TODO document Roll takes an application service level view of the world. What does a typical application service need? DNS, KMS, ASG. Iterate and go through them.
;; TODO document the modules that Roll comes with
;; TODO add generate sample to lumo, splits out to a file, pretty printed.

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
   :services {:foo-service (assoc (gen/generate (s/gen :roll.core/service))
                                  :availability-zones ["eu-west-1a" "eu-west-1b"])}
   :asgs [{:service :foo-service
           :load-balancer :foo-service
           :release-artifact (gen/generate (s/gen string?))
           :version (gen/generate (s/gen string?))}]})

(defn fmt [s & args]
  (apply #?(:clj format :cljs gstring/format) s args))

(deftest test-build-sample-terraform-deployment-config
  (let [config (sample-roll-config)
        version (-> config :asgs first :version)

        expected-tf {:provider {"aws" {:profile nil
                                       :region "eu-west-1"}},

                     :module { ;; The AutoScaling Group and Launch Configuration for the service
                              (fmt "foo-service_%s" version)
                              {:source "node_modules/@juxt/roll/tf/modules/asg"
                               :ami (-> config :services :foo-service :ami)
                               :key-name  (-> config :services :foo-service :key-name)
                               :instance-type (-> config :services :foo-service :instance-type)
                               :instance-count (-> config :services :foo-service :instance-count)
                               :availability-zones (-> config :services :foo-service :availability-zones)
                               :security-group-id "${module.foo-service_security.id}"
                               :iam-instance-profile "${module.foo-service_security.iam_instance_profile}"
                               :user-data (fmt "${data.template_file.foo-service_%s_user_data.rendered}" version)
                               :environment (fmt "%s-foo-service-%s" (-> config :environment) version)
                               :target_group_arns ["${module.foo-service_alb_target.arn}"]}

                              ;; Security Group for ALB
                              (fmt "foo-service_%s_alb_security_group" (-> config :load-balancers :foo-service first :listen))
                              {:name "foo-service"
                               :environment (-> config :environment)
                               :listen_port  (-> config :load-balancers :foo-service first :listen)
                               :source "node_modules/@juxt/roll/tf/modules/alb_security_group"}

                              ;; ALB Target:
                              "foo-service_alb_target"
                              {:name "foo-service"
                               :environment "TgGPnh3mDND"
                               :security_groups '("${module.foo-service_8158_alb_security_group.id}")
                               :vpc_id "7e889I1Ong4CTf8iNzCQo0wiI0wvP0"
                               :subnet_ids ["subnet1" "subnet2"]
                               :source "node_modules/@juxt/roll/tf/modules/alb_target"}

                              ;; Can't remember what this is:
                              "foo-service_0_alb_front"
                              {:listen-port 8158
                               :protocol "HTTP"
                               :ssl-policy nil
                               :certificate-arn nil
                               :target_group_arn "${module.foo-service_alb_target.arn}"
                               :load_balancer_arn "${module.foo-service_alb_target.load_balancer_arn}"
                               :source "node_modules/@juxt/roll/tf/modules/alb_front"}

                              ;; Route 53 Alias:
                              "z0xQ2zwgGA1HCiVs2FV8_route53_alias"
                              {:name "z0xQ2zwgGA1HCiVs2FV8"
                               :zone-id "Wc19ugRO8kAz36KpN",
                               :target-dns-name "${module.foo-service_alb_target.dns_name}"
                               :target-zone-id "${module.foo-service_alb_target.zone_id}"
                               :source "node_modules/@juxt/roll/tf/modules/route53record"},

                              ;; Security for ASG
                              "foo-service_security"
                              {:environment "TgGPnh3mDND-foo-service",
                               :port 8080,
                               :source "node_modules/@juxt/roll/tf/modules/security"}

                              ;; The Bastion, used for deployment purposes:
                              "bastion"
                              {:environment "TgGPnh3mDND"
                               :key-name nil
                               :user-data nil
                               :source "node_modules/@juxt/roll/tf/modules/bastion"}

                              ;; KMS Key for the Service
                              "kms-key"
                              {:alias "TgGPnh3mDND"
                               :root-arn "o2D2ZcwfPrK3al8ZR06w"
                               :admin-arns ["q091pCsG73F6xvHvvT"]
                               :user-arns '("${module.bastion.role_arn}" "${module.foo-service_security.role_arn}")
                               :attachment-arns '("${module.bastion.role_arn}" "${module.foo-service_security.role_arn}")
                               :source "node_modules/@juxt/roll/tf/modules/kms"}}

                     :resource {:aws-iam-role-policy {"foo-service"
                                                      {:name "TgGPnh3mDND-foo-service"
                                                       :role "${module.foo-service_security.role_id}"
                                                       :policy "{\n    \"Statement\": [\n        {\n            \"Effect\": \"Allow\",\n            \"Action\": [\n                \"s3:GetObject\"\n            ],\n            \"Resource\": [\n                \"arn:aws:s3:::N33xQZ/*\"\n            ]\n        }\n    ]\n}"}}},

                     :data {:template-file {"foo-service_z1k0gTi5Frq80k18Vax_user-data"
                                            {:template "${file(\"node_modules/@juxt/roll/tf/files/run-server.sh\")}",
                                             :vars {:launch_command nil,
                                                    :release_artifact "ulHWBCuM62R79k7c82",
                                                    :releases_bucket "N33xQZ"}}}}}]
    (let [actual-tf (roll.core/deployment->tf config)]
      (is (= (-> expected-tf
                 (select-keys [:module :provider])
                 (update :module select-keys [(fmt "foo-service_%s" version)
                                              (fmt "foo-service_%s_alb_security_group" (-> config :load-balancers :foo-service first :listen))]))

             (-> actual-tf
                 (select-keys [:module :provider])
                 (update :module select-keys [(fmt "foo-service_%s" version)
                                              (fmt "foo-service_%s_alb_security_group" (-> config :load-balancers :foo-service first :listen))])))))))
