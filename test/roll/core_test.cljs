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
;; TODO add generate sample to lumo, splits out to a file, pretty printed. show all the generated TF

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
   :route-53-aliases [(-> (s/gen :roll.core/route-53-alias)
                          (gen/generate)
                          (assoc :load-balancer :foo-service
                                 :name-prefix "foo")
                          (dissoc :module-name))]
   :services {:foo-service (assoc (gen/generate (s/gen :roll.core/service))
                                  :availability-zones ["eu-west-1a" "eu-west-1b"])}
   :asgs [{:service :foo-service
           :load-balancer :foo-service
           :release-artifact (gen/generate (s/gen string?))
           :version (gen/generate (s/gen string?))}]})

(defn fmt [s & args]
  (apply #?(:clj format :cljs gstring/format) s args))

(defn- expected-tf-asg-module [config]
  ;; The AutoScaling Group and Launch Configuration for the service
  (let [version (-> config :asgs first :version)]
    [(fmt "foo-service_%s" version) {:source "node_modules/@juxt/roll/tf/modules/asg"
                                     :ami (-> config :services :foo-service :ami)
                                     :key-name  (-> config :services :foo-service :key-name)
                                     :instance-type (-> config :services :foo-service :instance-type)
                                     :instance-count (-> config :services :foo-service :instance-count)
                                     :availability-zones (-> config :services :foo-service :availability-zones)
                                     :security-group-id "${module.foo-service_security.id}"
                                     :iam-instance-profile "${module.foo-service_security.iam_instance_profile}"
                                     :user-data (fmt "${data.template_file.foo-service_%s_user_data.rendered}" version)
                                     :environment (fmt "%s-foo-service-%s" (-> config :environment) version)
                                     :target_group_arns ["${module.foo-service_alb_target.arn}"]}]))

(deftest test-build-sample-terraform-deployment-config
  (let [config (sample-roll-config)

        ;; Pull some useful stuff out:
        version (-> config :asgs first :version)
        lb-port (-> config :load-balancers :foo-service first :listen)

        expected-tf {:provider {"aws" {:profile nil
                                       :region "eu-west-1"}},

                     :module {;; Security Group for ALB
                              (fmt "foo-service_%s_alb_security_group" lb-port)
                              {:name "foo-service"
                               :environment (-> config :environment)
                               :listen_port lb-port
                               :source "node_modules/@juxt/roll/tf/modules/alb_security_group"}

                              ;; Application Load Balancer and Target Group:
                              "foo-service_alb_target"
                              {:source "node_modules/@juxt/roll/tf/modules/alb_target"
                               :name "foo-service"
                               :environment (-> config :environment)
                               :security_groups [(fmt "${module.foo-service_%s_alb_security_group.id}" lb-port)]
                               :vpc_id (-> config :vpc-id)
                               :subnet_ids (-> config :subnets)}

                              ;; Application Load Balancer Listener
                              "foo-service_0_alb_front"
                              (merge (select-keys (-> config :load-balancers :foo-service first)
                                                  [:ssl-policy :certificate-arn :protocol])
                                     {:source "node_modules/@juxt/roll/tf/modules/alb_front"
                                      :listen-port lb-port
                                      :target_group_arn "${module.foo-service_alb_target.arn}"
                                      :load_balancer_arn "${module.foo-service_alb_target.load_balancer_arn}"})

                              ;; Route 53 Alias:
                              (fmt "%s_route53_alias" (-> config :route-53-aliases first :name-prefix))
                              {:name (-> config :route-53-aliases first :name-prefix)
                               :zone-id (-> config :route-53-aliases first :zone-id)
                               :target-dns-name "${module.foo-service_alb_target.dns_name}"
                               :target-zone-id "${module.foo-service_alb_target.zone_id}"
                               :source "node_modules/@juxt/roll/tf/modules/route53record"},

                              ;; Security for ASG
                              "foo-service_security"
                              {:environment (fmt "%s-foo-service" (-> config :environment)),
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

    (let [actual-tf (-> config roll.core/deployment->tf)]

      (testing "Complete generation"
        ;; TODO do a full comparison
        )

      (testing "ASG service security"
        (is (= (get-in expected-tf [:module "foo-service_security"])
               (get-in actual-tf [:module "foo-service_security"]))))

      (testing "Autoscaling group module for each service version"
        (let [[k m] (expected-tf-asg-module config)]
          (is (= m (-> config roll.core/deployment->tf :module (get k))))))

      (testing "ALB security group"
        (is (= (get-in expected-tf [:module (fmt "foo-service_%s_alb_security_group" lb-port)])
               (get-in actual-tf [:module (fmt "foo-service_%s_alb_security_group" lb-port)]))))

      (testing "ALB and target group"
        (is (= (get-in expected-tf [:module "foo-service_alb_target"])
               (get-in actual-tf [:module "foo-service_alb_target"]))))

      ;; (testing "ALB listeners"
      ;;   (is (= (get-in expected-tf [:module "foo-service_0_alb_front"])
      ;;          (get-in actual-tf [:module "foo-service_0_alb_front"]))))

      (testing "Route 53 alias generation "
        (let [module-name (fmt "%s_route53_alias" (-> config :route-53-aliases first :name-prefix))]
          (is (= (get-in expected-tf [:module module-name])
                 (get-in actual-tf [:module module-name]))))))))
