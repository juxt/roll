;; Copyright © 2016-2017, JUXT LTD.

(ns roll.core
  (:require [cljs.nodejs :as nodejs]
            [clojure.string :as str]
            [clojure.walk :refer [postwalk]]
            [cljs.spec.alpha :as s]
            [cljs.reader :as reader]
            [roll.modules.route-53]
            [roll.modules.asg]
            [roll.modules.alb]
            [roll.modules.service-security]
            [roll.modules.kms]
            [roll.modules.vpc]
            [roll.modules.bastion]
            [cljs.pprint :as pprint]
            [roll.utils :refer [->json]]
            [meta-merge.core :refer [meta-merge]]
            [lumo.io :as io]))

;; The environment is used as a prefix to segregate all the named constructs in AWS
(s/def ::environment string?)
;; We take a releases bucket, this is used to fetch release-artifacts
;; for each EC2 node prior to launching the application
(s/def ::releases-bucket string?)
;; The vpc-id is required by AWS Auto Scaling Groups
(s/def ::vpc-id string?)
;; Subnet IDs - required for ALBs (load balancers)
(s/def ::subnet-ids (s/coll-of string? :kind vector?))

;; Use for AWS provider
(s/def ::aws-profile string?)
;; Use for AWS provider
(s/def ::aws-region string?)

;; KMS Root User Arn
(s/def :kms/root string?)
;; KMS Key administrators
(s/def :kms/admins (s/coll-of string? :kind vector?))
(s/def ::kms (s/keys :req-un [:kms/root :kms/admins]))

(s/def :load-balancer/listen (s/int-in 1 65536))
(s/def :load-balancer/forward int?)
(s/def :load-balancer/protocol #{"HTTP" "HTTPS"})
(s/def :load-balancer/ssl-policy string?)
(s/def :load-balancer/certificate-arn string?)
(s/def ::load-balancer (s/keys :req-un [:load-balancer/listen
                                        :load-balancer/forward
                                        :load-balancer/protocol]
                               :opt-un [:load-balancer/ssl-policy
                                        :load-balancer/certificate-arn]))
(s/def ::load-balancers (s/map-of keyword? (s/coll-of ::load-balancer)))

(s/def :route-53-alias/resource-name (s/and string? #(re-matches #"^(\w|-)+$" %)))
(s/def :route-53-alias/name-prefix string?)
(s/def :route-53-alias/zone-id string?)
(s/def :route-53-alias/load-balancer keyword?)
(s/def ::route-53-alias (s/keys :req-un [:route-53-alias/name-prefix
                                         :route-53-alias/zone-id
                                         :route-53-alias/load-balancer]
                                :opt-un [:route-53-alias/resource-name]))
(s/def ::route-53-aliases (s/coll-of ::route-53-alias))

(s/def :service/ami string?)
(s/def :service/instance-type string?)
(s/def :service/key-name string?)
(s/def :service/instance-count int?)
(s/def :service/availability-zones (s/coll-of string? :kind vector?))
(s/def :service/port int?)

(s/def :launch-config/jvm-opts (s/coll-of string? :kind vector?))
(s/def :launch-config/launch-command string?)
(s/def :launch-config/args (s/keys :opt-un [:launch-config/jvm-opts
                                            :launch-config/launch-command]))
(s/def :launch-config/template #{:default :java8})
(s/def :service/launch-config (s/keys :req-un [:launch-config/template]
                                      :opt-un [:launch-config/args]))

(s/def :service/user-data string?)

;; Todo how?:
;;(s/def :service/user-data-or-launch-config (s/or :service/user-data :service/launch-config))

(s/def ::service (s/keys :req-un [:service/instance-type
                                  :service/key-name
                                  :service/instance-count
                                  :service/availability-zones
                                  :service/port]
                         :opt-un [:service/user-data
                                  :service/launch-config
                                  :service/ami]))
(s/def ::services (s/map-of keyword? ::service))

(s/def :asg/service keyword?)
(s/def :asg/release-artifact string?)
(s/def :asg/version string?)
(s/def :asg/load-balancer keyword?)
(s/def ::asg (s/keys :req-un [:asg/service :asg/release-artifact :asg/version]
                     :opt-un [:asg/load-balancer]))
(s/def ::asgs (s/coll-of ::asg))

(s/def :bastion/key-name string?)
(s/def :bastion/user-data string?)
(s/def ::bastion (s/keys :req-un [:bastion/key-name]
                         :opt-un [:bastion/user-data]))

(s/def :subnet/availability-zone string?)
(s/def :subnet/index int?)

(s/def ::subnet (s/keys :req-un [:subnet/availability-zone
                                 :subnet/index]))
(s/def ::subnets (s/map-of string? ::subnet))

(s/def ::config (s/keys :req-un [::environment
                                 ::releases-bucket
                                 ::services
                                 ::aws-region
                                 ::aws-profile]
                        :opt-un [::vpc-id
                                 ::subnet-ids
                                 ::bastion
                                 ::route-53-aliases
                                 ::kms
                                 ::load-balancers
                                 ::asgs
                                 ::subnets]))

;; The minimum config required prior to preprocessing/enrichin
(s/def ::base-config (s/keys :req-un [::aws-profile]))

(def child_process (cljs.nodejs/require "child_process"))

(defn sh [args]
  (let [result (.spawnSync child_process
                           (first args)
                           (clj->js (rest args))
                           #js {"shell" true})]

    (when-not (= 0 (.-status result))
      (do (println (.toString (.-stderr result) "utf8"))
          (throw (js/Error. (str "Error whilst performing shell command: " (pr-str args))))))

    (str (.-stdout result))))

(defn- aws-cmd [{:keys [aws-profile] :as config} & parts]
  (let [cmd (vec (concat ["aws"] parts ["--profile" aws-profile]))]
    (js->clj (js/JSON.parse (sh cmd)))))

(defn upload!
  "Uploads a release artifact to S3, preserving version metadata."
  [{:keys [releases-bucket aws-profile]} local-path s3-name version]
  (let [dest (str "s3://" releases-bucket "/" s3-name)]
    (println "Uploading" local-path "to" dest)
    (sh ["aws" "s3" "cp" local-path dest
         "--metadata" (str "Version=" version)
         "--profile" aws-profile])))

(defn git-version []
  (let [result (.spawnSync child_process
                           "git"
                           (clj->js ["describe" "--dirty" "--long" "--tags" "--match" "[0-9]*"])
                           #js {"shell" true})]

    (cond (= 0 (.-status result))
          (.trim (str (.-stdout result)))

          (re-find #"No names found" (.toString (.-stderr result) "utf8"))
          (.trim (sh ["git rev-parse --short HEAD"]))

          :else
          (throw (js/Error. "Could not determine git version, try adding a commit.")))))

(defn resolve-region [{:keys [aws-profile aws-region] :as config}]
  (when-not aws-region
    (assoc config :aws-region
           (.trim (sh (vec (concat ["aws" "configure" "get" "region"]
                                   ["--profile" aws-profile])))))))

(defn- resolve-vpc [{:keys [vpc-id]:as config}]
  (when (= vpc-id ::default)
    (assoc config :vpc-id (aws-cmd config
                                   "ec2" "describe-vpcs"
                                   "--query" "\"Vpcs[]|[0]|VpcId\"" "--filters" "Name=isDefault,Values=true"))))

(defn- resolve-subnet-ids
  "When the user has specified a VPC, but not the subnet-ids, we need to
  resolve the subnet-ids."
  [{:keys [subnet-ids vpc-id] :as config}]
  (when (and vpc-id (not subnet-ids))
    (assoc config :subnet-ids (mapv #(get % "SubnetId") (-> (aws-cmd config
                                                                     "ec2" "describe-subnets"
                                                                     "--filters" (str "\"Name=vpc-id,Values=" vpc-id "\""))
                                                            (get "Subnets"))))))

(defn- latest-release-artifact [config]
  (->> (str/split (sh (vec (concat ["aws" "s3" "ls" (:releases-bucket config)]
                                   ["--profile" (-> config :aws-profile)]))) "\n")
       (map #(str/split % #"\s+"))
       (sort-by (juxt first second))
       last
       last
       str))

(defn- resolve-latest-release-artifact [config]
  (update-in config [:asgs] (fn [asgs]
                              (for [{:keys [release-artifact] :as asg} asgs]
                                (if (= ::latest-release-artifact release-artifact)
                                  (assoc asg :release-artifact (latest-release-artifact config))
                                  asg)))))

(defn- version-for-artifact [{:keys [releases-bucket] :as config} release-artifact]
  (some-> (aws-cmd config
                   "s3api" "head-object"
                   "--bucket" releases-bucket
                   "--key" release-artifact)
          (get-in ["Metadata" "version"])
          (clojure.string/replace #"\.|\-" "_")))

(defn- resolve-asg-versions
  "If the release artifact has version meta data associated with it in
  S3, use this as the version to attach to the autoscaling group."
  [config]
  (update-in config [:asgs] (fn [asgs]
                              (for [{:keys [release-artifact version] :as asg} asgs]
                                (if-not version
                                  (assoc asg :version (version-for-artifact config release-artifact))
                                  asg)))))

(defn- fetch-default-availability-zones
  [config]
  (mapv #(get % "ZoneName")
        (-> (aws-cmd config
                     "ec2" "describe-availability-zones"
                     "--filters" (str "Name=region-name,Values=" (:aws-region config)))
            (get "AvailabilityZones"))))

(defn- resolve-service-availability-zones
  "Autoscaling groups derived from services require
  availability-zones. The default availability-zones is used if not
  specified directly on the individual service."
  [{:keys [services] :as config}]
  (when (not-empty (remove :availability-zones (vals services)))
    (let [default-availability-zones (or (:availability-zones config)
                                         (fetch-default-availability-zones config))]
      (assoc config :services (into {}
                                    (for [[k service] services]
                                      [k (assoc service :availability-zones (or (:availability-zones service)
                                                                                default-availability-zones))]))))))

(def ^:private
  region-ami-lookup
  (reader/read-string (io/slurp (io/resource "roll/ami-lookup.edn"))))

(defn- resolve-service-ami
  [config]
  (update config
          :services
          (fn [services]
            (into {}
                  (for [[k service] services]
                    [k (assoc service :ami (region-ami-lookup (:aws-region config)))])))))

(defn- enrich-with-subnets
  [{:keys [vpc-id subnets] :as config}]
  (let [availability-zones (sort (distinct (mapcat :availability-zones (vals (:services config)))))]
    (when (and (not vpc-id) (not subnets))
      (assoc config :subnets
             (into {}
                   (for [[idx availability-zone] (map-indexed vector availability-zones)
                         :let [idx (inc idx)]]
                     [availability-zone
                      {:availability-zone availability-zone
                       :index idx}]))))))

(defn preprocess [config & [{:keys [cache-file] :as opts}]]
  (when (= ::s/invalid (s/conform ::base-config config))
    (throw (js/Error. (str "Invalid input: " (prn-str (s/explain-data ::base-config config))))))

  (or (and cache-file (fs.existsSync cache-file) (reader/read-string (fs.readFileSync cache-file "utf-8")))
      (let [config (reduce #(or (%2 %1) %1) config [resolve-region
                                                    resolve-latest-release-artifact
                                                    resolve-asg-versions
                                                    resolve-vpc
                                                    resolve-subnet-ids
                                                    resolve-service-availability-zones
                                                    resolve-service-ami
                                                    enrich-with-subnets])]
        (when cache-file
          (fs.writeFileSync cache-file config))
        config)))

(defn ->tf-json
  "Convert the map into Terraform styled JSON."
  [m]
  (->> m
       (postwalk (fn [x]
                   (if (map? x)
                     (zipmap (map #(str/replace (name %) "-" "_") (keys x)) (vals x))
                     x)))
       ->json))

(defn- config->tf [{:keys [aws-profile aws-region] :as config}]
  (meta-merge {:provider {"aws" {:profile aws-profile
                                 :region aws-region}}}
              (roll.modules.vpc/generate config)
              (roll.modules.route-53/generate config)
              (roll.modules.alb/generate config)
              (roll.modules.asg/generate config)
              (roll.modules.kms/generate config)
              (roll.modules.service-security/generate config)
              (roll.modules.bastion/generate config)))

(defn deployment->tf [{:keys [environment releases-bucket aws-profile aws-region] :as config}]
  (when (= ::s/invalid (s/conform ::config config))
    (println (s/explain-data ::config config))
    (throw (js/Error. (str "Invalid input: " (prn-str (s/explain-data ::config config))))))

  (config->tf config))
