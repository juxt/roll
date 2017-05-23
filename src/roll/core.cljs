;; Copyright © 2016-2017, JUXT LTD.

(ns roll.core
  (:require [cljs.nodejs :as nodejs]
            [clojure.string :as str]
            [clojure.walk :refer [postwalk]]
            [cljs.spec :as s]
            [cljs.reader :as reader]
            [roll.modules.route-53]
            [roll.modules.asg]
            [roll.modules.alb]
            [roll.modules.service-security]
            [roll.modules.kms]
            [roll.modules.bastion]
            [cljs.pprint :as pprint]
            [roll.utils :refer [->json]]
            [meta-merge.core :refer [meta-merge]]))

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
(s/def ::aws-profile (s/nilable string?))
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
(s/def :bastion/user-data string?)
(s/def ::bastion (s/keys :req-un [:bastion/key-name]
                         :opt-un [:bastion/user-data]))

(s/def ::config (s/keys :req-un [::environment
                                 ::releases-bucket
                                 ::vpc-id
                                 ::subnets
                                 ::services
                                 ::asgs
                                 ::aws-region
                                 ::aws-profile]
                        :opt-un [::bastion
                                 ::route-53-aliases
                                 ::kms
                                 ::load-balancers]))

(def child_process (cljs.nodejs/require "child_process"))

(defn sh [args]
  (let [result (.spawnSync child_process
                           (first args)
                           (clj->js (rest args))
                           #js {"shell" true})]

    (when-not (= 0 (.-status result))
      (do (println (.toString (.-stderr result) "utf8"))
          (throw (js/Error. (str "Error whilst performing shell command.")))))

    (str (.-stdout result))))

(defn- latest-artifact [config]
  (->> (str/split (sh (vec (concat ["aws" "s3" "ls" (:releases-bucket config)]
                                   (when (-> config :aws-profile)
                                     ["--profile" (-> config :aws-profile)])))) "\n")
       (map #(str/split % #"\s+"))
       (sort-by (juxt first second))
       last
       last
       str))

(defn- aws-cmd [{:keys [aws-profile] :as config} & parts]
  (let [cmd (vec (concat ["aws"] parts (when aws-profile
                                         ["--profile" aws-profile])))]
    (js->clj (js/JSON.parse (sh cmd)))))

(defn resolve-region [{:keys [aws-profile aws-region] :as config}]
  (when-not aws-region
    (println "Resolving AWS Region")
    (assoc config :aws-region
           (.trim (sh (vec (concat ["aws" "configure" "get" "region"]
                                   (when aws-profile
                                     ["--profile" aws-profile]))))))))

(defn- resolve-vpc [{:keys [vpc-id]:as config}]
  (when-not vpc-id
    (println "Resolving VPC ID")
    (assoc config :vpc-id (aws-cmd config
                                   "ec2" "describe-vpcs"
                                   "--query" "\"Vpcs[]|[0]|VpcId\"" "--filters" "Name=isDefault,Values=true"))))

(defn- resolve-subnets [{:keys [subnets vpc-id] :as config}]
  (when-not subnets
    (println "Resolving Subnets")
    (assoc config :subnets (mapv #(get % "SubnetId") (-> (aws-cmd config
                                                                  "ec2" "describe-subnets"
                                                                  "--filters" (str "\"Name=vpc-id,Values=" vpc-id "\""))
                                                         (get "Subnets"))))))

(defn- resolve-release-artefacts [config]
  (update-in config [:asgs] (fn [asgs] (for [{:keys [release-artifact] :as asg} asgs]
                                         (if (= :latest release-artifact)
                                           (assoc asg :release-artifact (latest-artifact config)) asg)))))

(defn preprocess [config & [{:keys [cache-file] :as opts}]]
  (or (and cache-file (fs.existsSync cache-file) (reader/read-string (fs.readFileSync cache-file "utf-8")))
      (let [config (reduce #(or (%2 %1) %1) config [resolve-region
                                                    resolve-release-artefacts
                                                    resolve-vpc
                                                    resolve-subnets])]
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
              (roll.modules.route-53/generate config)
              (roll.modules.alb/generate config)
              (roll.modules.asg/generate config)
              (roll.modules.kms/generate config)
              (roll.modules.service-security/generate config)
              (roll.modules.bastion/generate config)))

(defn deployment->tf [{:keys [environment releases-bucket aws-profile aws-region] :as config}]
  (when (= ::s/invalid (s/conform ::config config))
    (println (s/explain-data ::config config))
    (throw (ex-info "Invalid input" (s/explain-data ::config config))))

  (pprint/pprint
   (config->tf config))

  (config->tf config))

;; TODO pull this out of Roll - see cp
(def fs (nodejs/require "fs"))
(def mustache (cljs.nodejs/require "mustache"))
(defn render-mustache [template-file m]
  (.render mustache (fs.readFileSync template-file "utf-8") (clj->js m)))
