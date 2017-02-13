(ns roll.core
  (:require [cljs.nodejs :as nodejs]
            [clojure.string :as str]))

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

(defn resolve-path [{:keys [domain profile]} path]
  (clojure.string/join "_"  (map name (concat [domain profile] path))))

(defn module [config roll-home path module m]
  [(resolve-path config path) (assoc m :source (str roll-home "/tf/modules/" (name module)))])

(defn ref-module-var [config path var]
  (str "${module." (resolve-path config path) "." var "}"))

(defn render-template [config path]
  (str "${data.template_file." (resolve-path config path) ".rendered}"))

(defn preprocess [config]
  (-> config
      (update-in [:asgs] (fn [asgs] (for [{:keys [release-artifact] :as asg} asgs]
                                      (if (= :latest release-artifact)
                                        (assoc asg :release-artifact (latest-artifact config)) asg))))))

(defn deployment->tf [{:keys [profile releases-bucket] :as config} roll-home opts]
  (let [environment (str (:domain config) "-" (name profile))

        ;; Redefine helper fns:
        resolve-path (partial resolve-path config)
        module (partial module config roll-home)
        ref-module-var (partial ref-module-var config)
        render-template (partial render-template config)]
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
                                  :user-data (render-template [service version "user_data"])}))
                      (when load-balancer
                        {:target_group_arns [(ref-module-var [load-balancer "alb"] "arn")]})))

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

            ;; Create the encryption key for this domain and allow each service to use it, including the bastion
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
                :policy (mach.core/json
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
                       :releases_bucket releases-bucket}}]))}}))

(def fs (nodejs/require "fs"))
(def mustache (cljs.nodejs/require "mustache"))

(defn render-mustache [template-file m]
  (.render mustache (fs.readFileSync template-file "utf-8") (clj->js m)))
