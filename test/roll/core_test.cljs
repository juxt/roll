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

(defn fmt [s & args]
  (apply #?(:clj format :cljs gstring/format) s args))

(defn- expected-tf-asg-module [config]
  ;; The AutoScaling Group and Launch Configuration for the service
  (let [version (-> config :asgs first :version)]
    [(fmt "foo-service_%s" version)
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
      :target_group_arns ["${module.foo-service_alb_target.arn}"]}]))

(defn- expected-service-security-module [config]
  ;; Security for ASG
  ["foo-service_security"
   {:environment (fmt "%s-foo-service" (-> config :environment)),
    :port 8080,
    :source "node_modules/@juxt/roll/tf/modules/security"}])

(defn- expected-alb-security-module [config]
  ;; Security Group for ALB
  (let [lb-port (-> config :load-balancers :foo-service first :listen)]
    [(fmt "foo-service_%s_alb_security_group" lb-port)
     {:name "foo-service"
      :environment (-> config :environment)
      :listen_port lb-port
      :source "node_modules/@juxt/roll/tf/modules/alb_security_group"}]))

(defn expected-alb-target [config]
  ;; Application Load Balancer and Target Group:
  (let [lb-port (-> config :load-balancers :foo-service first :listen)]
    ["foo-service_alb_target"
     {:source "node_modules/@juxt/roll/tf/modules/alb_target"
      :name "foo-service"
      :environment (-> config :environment)
      :security_groups [(fmt "${module.foo-service_%s_alb_security_group.id}" lb-port)]
      :vpc_id (-> config :vpc-id)
      :subnet_ids (-> config :subnets)}]))

(defn expected-alb-listener [config]
  (let [lb-port (-> config :load-balancers :foo-service first :listen)]
    ["foo-service_0_alb_front"
     (merge (select-keys (-> config :load-balancers :foo-service first)
                         [:ssl-policy :certificate-arn :protocol])
            {:source "node_modules/@juxt/roll/tf/modules/alb_front"
             :listen-port lb-port
             :target_group_arn "${module.foo-service_alb_target.arn}"
             :load_balancer_arn "${module.foo-service_alb_target.load_balancer_arn}"})]))

(defn- expected-kms-key-for-environment [config]
  ;; KMS Key for the Service
  ["kms-key"
   {:alias (-> config :environment)
    :root-arn (-> config :kms :root)
    :admin-arns (-> config :kms :admins)
    :user-arns ["${module.bastion.role_arn}" "${module.foo-service_security.role_arn}"]
    :attachment-arns ["${module.bastion.role_arn}" "${module.foo-service_security.role_arn}"]
    :source "node_modules/@juxt/roll/tf/modules/kms"}])

(defn- expected-route-53-module [config]
  [(fmt "%s_route53_alias" (-> config :route-53-aliases first :name-prefix))
   {:name (-> config :route-53-aliases first :name-prefix)
    :zone-id (-> config :route-53-aliases first :zone-id)
    :target-dns-name "${module.foo-service_alb_target.dns_name}"
    :target-zone-id "${module.foo-service_alb_target.zone_id}"
    :source "node_modules/@juxt/roll/tf/modules/route53record"}],)

(defn- expected-bastion-module [config]
  ;; The Bastion, used for deployment purposes:
  ["bastion"
   {:source "node_modules/@juxt/roll/tf/modules/bastion"
    :environment (-> config :environment)
    :key-name (-> config :bastion :key-name)
    :user-data (-> config :bastion :user-data)}])

(defn- expected-resources-iam-role-policies [config]
  {:aws-iam-role-policy {"foo-service"
                         {:name (fmt "%s-foo-service" (-> config :environment))
                          :role "${module.foo-service_security.role_id}"
                          :policy (fmt "{\n    \"Statement\": [\n        {\n            \"Effect\": \"Allow\",\n            \"Action\": [\n                \"s3:GetObject\"\n            ],\n            \"Resource\": [\n                \"arn:aws:s3:::%s/*\"\n            ]\n        }\n    ]\n}"
                                       (-> config :releases-bucket))}}})

(defn- expected-data-launch-scripts [config]
  {:template-file {(fmt "foo-service_%s_user-data" (-> config :asgs first :version))
                   {:template "${file(\"node_modules/@juxt/roll/tf/files/run-server.sh\")}",
                    :vars {:launch_command nil,
                           :release_artifact (-> config :asgs first :release-artifact)
                           :releases_bucket (-> config :releases-bucket)}}}})

(defn- expected-provider-aws [config]
  {"aws" {:profile nil
          :region "eu-west-1"}})

(deftest test-build-sample-terraform-deployment-config
  (let [config (sample-roll-config)]

    (testing "AWS Provider"
      (is (= (expected-provider-aws config)
             (-> config roll.core/deployment->tf :provider))))

    (testing "Launch scripts for each Service"
      (is (= (expected-data-launch-scripts config)
             (-> config roll.core/deployment->tf :data))))

    (testing "IAM role for each service"
      (is (= (expected-resources-iam-role-policies config)
             (-> config roll.core/deployment->tf :resource))))

    (testing "KMS for environment"
      (let [[k m] (expected-kms-key-for-environment config)]
        (is (= m (-> config roll.core/deployment->tf :module (get k))))))

    (testing "ASG service security"
      (let [[k m] (expected-service-security-module config)]
        (is (= m (-> config roll.core/deployment->tf :module (get k))))))

    (testing "Autoscaling group module for each service version"
      (let [[k m] (expected-tf-asg-module config)]
        (is (= m (-> config roll.core/deployment->tf :module (get k))))))

    (testing "ALB security group"
      (let [[k m] (expected-alb-security-module config)]
        (is (= m (-> config roll.core/deployment->tf :module (get k))))))

    (testing "ALB and target group"
      (let [[k m] (expected-alb-target config)]
        (is (= m (-> config roll.core/deployment->tf :module (get k))))))

    (testing "ALB listener")
    (let [[k m] (expected-alb-listener config)]
      (is (= m (-> config roll.core/deployment->tf :module (get k)))))

    (testing "Route 53 alias generation"
      (let [[k m] (expected-route-53-module config)]
        (is (= m (-> config roll.core/deployment->tf :module (get k))))))

    (testing "Bastion module"
      (let [[k m] (expected-bastion-module config)]
        (is (= m (-> config roll.core/deployment->tf :module (get k))))))

    (testing "Complete test"
      (let [[k m] (expected-bastion-module config)]
        (is (= {:data (expected-data-launch-scripts config)
                :resource (expected-resources-iam-role-policies config)
                :provider (expected-provider-aws config)
                :module (into {} [(expected-kms-key-for-environment config)
                                  (expected-service-security-module config)
                                  (expected-tf-asg-module config)
                                  (expected-alb-security-module config)
                                  (expected-alb-target config)
                                  (expected-alb-listener config)
                                  (expected-route-53-module config)
                                  (expected-bastion-module config)])}
               (-> config roll.core/deployment->tf)))))))
