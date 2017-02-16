;; Copyright © 2016-2017, JUXT LTD.

(ns roll.core
  (:require [cljs.nodejs :as nodejs]
            [clojure.string :as str]
            [clojure.walk :refer [postwalk]]
            [cljs.spec :as s]))

;; The environment is used as a prefix to segregate all the named constructs in AWS
(s/def ::environment string?)
;; We take a releases bucket, this is used to fetch release-artifacts
;; for each EC2 node prior to launching the application
(s/def ::releases-bucket string?)
;; The vpc-id is required by AWS Auto Scaling Groups
(s/def ::vpc-id string?)
;; Subnets - required for Auto Scaling Groups. TODO can we fetch these?
(s/def ::subnets (s/coll-of string? :kind vector?))

;; Use for AWS provider
(s/def :common/aws-profile string?)
;; Use for AWS provider
(s/def :common/aws-region string?)
;; TODO flatten/remove common:
(s/def ::common (s/keys :req-un [:common/aws-profile :common/aws-region]))

;; KMS Root User Arn
(s/def :kms/root string?)
;; KMS Key administrators
(s/def :kms/admins (s/coll-of string? :kind vector?))
(s/def ::kms (s/keys :req-un [:kms/root :kms/admins]))

(s/def :load-balancer/listen int?)
(s/def :load-balancer/forward int?)
(s/def :load-balancer/protocol #{"HTTP" "HTTPS"})
(s/def :load-balancer/ssl-policy string?)
(s/def :load-balancer/certificate-arn string?)
(s/def ::load-balancer (s/keys :req-un [:load-balancer/listen
                                        :load-balancer/forward
                                        :load-balancer/protocol
                                        :load-balancer/ssl-policy
                                        :load-balancer/certificate-arn]))
(s/def ::load-balancers (s/map-of keyword? ::load-balancer))

(s/def :route-53-alias/name-prefix string?)
(s/def :route-53-alias/zone-id string?)
(s/def :route-53-alias/load-balancer keyword?)
(s/def ::route-53-alias (s/keys :req-un [:route-53-alias/name-prefix
                                        :route-53-alias/zone-id
                                        :route-53-alias/load-balancer]))
(s/def ::route-53-aliases (s/coll-of ::route-53-alias))

(s/def :service/ami string?)
(s/def :service/instance-type string?)
(s/def :service/key-name string?)
(s/def :service/instance-count int?)
(s/def :service/availability-zones (s/coll-of string? :kind vector?))
(s/def ::service (s/keys :req-un [:service/ami
                                 :service/instance-type
                                 :service/key-name
                                 :service/instance-count
                                 :service/availability-zones]))
(s/def ::services (s/map-of keyword? ::service))

(s/def :asg/service keyword?)
(s/def :asg/release-artifact string?)
(s/def :asg/version string?)
(s/def :asg/load-balancer keyword?)
(s/def ::asg (s/keys :req-un [:asg/service :asg/release-artifact :asg/version]
                     :opt-un [:asg/load-balancer]))
(s/def ::asgs (s/coll-of ::asg))

(s/def :bastion/key-name string?)
(s/def ::bastion (s/keys :req-un [:bastion/key-name]))

(s/def ::config (s/keys :req-un [::environment
                                 ::releases-bucket
                                 ::vpc-id
                                 ::subnets
                                 ::kms
                                 ::common
                                 ::load-balancers
                                 ::route-53-aliases
                                 ::services
                                 ::asgs]))

(def child_process (cljs.nodejs/require "child_process"))
(defn sh [args]
  (let [result (.spawnSync child_process
                           (first args)
                           (clj->js (rest args))
                           #js {"shell" true})]
    (str (.-stdout result))))

(defn- latest-artifact [config]
  (->> (str/split (sh ["aws" "s3" "ls" (:releases-bucket config) "--profile" (-> config :common :aws-profile)]) "\n")
       (map #(str/split % #"\s+"))
       (sort-by (juxt first second))
       last
       last
       str))

(defn resolve-path [path]
  (clojure.string/join "_"  (map name path)))

(def roll-home "node_modules/@juxt/roll")

(defn module
  [path module m]
  [(resolve-path path)
   (assoc m :source (str roll-home "/tf/modules/" (name module)))])

(defn ref-module-var [path var]
  (str "${module." (resolve-path path) "." var "}"))

(defn render-template [path]
  (str "${data.template_file." (resolve-path path) ".rendered}"))

(defn preprocess [config]
  (-> config
      (update-in [:asgs] (fn [asgs] (for [{:keys [release-artifact] :as asg} asgs]
                                      (if (= :latest release-artifact)
                                        (assoc asg :release-artifact (latest-artifact config)) asg))))))

(defn ->json [m]
  (js/JSON.stringify (clj->js m) nil 4))

(defn ->tf-json
  "Convert the map into Terraform styled JSON."
  [m]
  (->> m
       (postwalk (fn [x]
                   (if (map? x)
                     (zipmap (map #(str/replace (name %) "-" "_") (keys x)) (vals x))
                     x)))
       ->json))

(defn deployment->tf [{:keys [environment releases-bucket] :as config}]
  (when (= ::s/invalid (s/conform ::config config))
    (println (s/explain-data ::config config))
    (throw (ex-info "Invalid input" (s/explain-data ::config config))))
  {:provider {"aws" {:profile (-> config :common :aws-profile)
                     :region (-> config :common :aws-region)}}
   :module
   (into {}
         (concat
          (for [{:keys [service version load-balancer] :as m} (:asgs config)]
            (module [service version] :asg
                    (-> config :services service
                        (select-keys [:instance-count :instance-type :ami :key-name :availability-zones])
                        (merge {:environment (str environment "-" (name service) "-" version)
                                :security-group-id (ref-module-var [service "security"] "id")
                                :iam-instance-profile (ref-module-var [service "security"] "iam_instance_profile")
                                :user-data (render-template [service version "user_data"])}
                               (when load-balancer
                                 {:target_group_arns [(ref-module-var [load-balancer "alb"] "arn")]})))))

          ;; Create ALBs:
          (for [[balancer {:keys [listen forward protocol ssl-policy certificate-arn]}] (:load-balancers config)]
            (module [balancer "alb"] :alb
                    {:name (name balancer)
                     :environment environment
                     :vpc_id (:vpc-id config)
                     :subnet_ids (:subnets config)
                     :listen-port listen
                     :forward-port forward
                     :protocol protocol
                     :ssl-policy ssl-policy
                     :certificate-arn certificate-arn}))

          ;; Create Alias Resource Record Sets
          (for [{:keys [name-prefix zone-id load-balancer]} (:route-53-aliases config)
                :let [_ (assert (get-in config [:load-balancers load-balancer]))]]
            (module [name-prefix "route53_alias"] :route53record
                    {:name name-prefix
                     :zone-id zone-id
                     :target-dns-name (ref-module-var [load-balancer "alb"] "dns_name")
                     :target-zone-id (ref-module-var [load-balancer "alb"] "zone_id")}))

          ;; Create IAM role for each service
          (for [service (keys (:services config))]
            (module [service "security"] :security
                    {:environment (str environment "-" (name service))
                     :port 8080}))

          ;; Creating the bastion
          [(module ["bastion"] :bastion
                   {:environment environment
                    :key-name (-> config :bastion :key-name)
                    :user-data (-> config :bastion :user-data)})]

          ;; Create the encryption key allow each service to use it, including the bastion
          (let [users (cons (ref-module-var ["bastion"] "role_arn")
                            (for [service (keys (:services config))]
                              (ref-module-var [service "security"] "role_arn")))]
            [(module ["kms-key"] :kms
                     {:alias environment
                      :root-arn (-> config :kms :root)
                      :admin-arns (-> config :kms :admins)
                      :user-arns users
                      :attachment-arns users})])))

   :resource
   { ;;Every service we run needs access to S3 to fetch releases, plus additional policies:
    :aws-iam-role-policy
    (into {}
          (for [[service {:keys [policies]}] (:services config)]
            [(resolve-path [service])
             {:name (str environment "-" (name service))
              :role (ref-module-var [service "security"] "role_id")
              :policy (->json
                       {:Statement (concat [{:Effect "Allow"
                                             :Action ["s3:GetObject"]
                                             :Resource [(str "arn:aws:s3:::" releases-bucket "/*")]}]
                                           policies)})}]))}

   ;; Create run scripts for each service/version
   :data
   {:template-file
    (into {}
          (for [{:keys [service version release-artifact launch-command]} (:asgs config)]
            [(resolve-path [service version "user-data"])
             {:template (str "${file(\"" roll-home "/tf/files/run-server.sh\")}")
              :vars {:launch_command launch-command
                     :release_artifact release-artifact
                     :releases_bucket releases-bucket}}]))}})

(def fs (nodejs/require "fs"))
(def mustache (cljs.nodejs/require "mustache"))

(defn render-mustache [template-file m]
  (.render mustache (fs.readFileSync template-file "utf-8") (clj->js m)))
