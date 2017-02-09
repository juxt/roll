(ns roll.core)

(defn deployment->tf [deployment-file roll-home opts]
  (let [{:keys [profile releases-bucket] :as config} (aero.core/read-config deployment-file {})
        environment (str (:domain config) "-" (name profile))

        ;; Define helper fns:
        resolve-path (fn [path] (clojure.string/join "_"  (map name (concat [(:domain config) profile] path))))
        module (fn [path module m] {(resolve-path path) (assoc m :source (str roll-home "/tf/modules/" (name module)))})
        ref-module-var (fn [path var] (str "${module." (resolve-path path) "." var "}"))
        render-template (fn [path] (str "${data.template_file." (resolve-path path) ".rendered}"))]
    {:module
     (concat
      (for [{:keys [service version load-balancer] :as m} (:services config)]
        (module [service version] :blue
                (merge
                 (dissoc m :service :version :load-balancer)
                 {:environment (str environment "-" service "-" version)
                  :security-group-id (ref-module-var [service "security"] "id")
                  :iam-instance-profile (ref-module-var [service "security"] "iam_instance_profile")
                  :user-data (render-template [service version "user_data"])}
                 (when load-balancer
                   {:target_group_arns [(ref-module-var [load-balancer "alb"] "arn")]}))))

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
      (for [service (distinct (map :service (:services config)))]
        (module [service "security"] :security
                {:environment (str environment "-" service)
                 :port 8080}))

      ;; Creating the bastion
      [(module ["bastion"] :bastion
               {:environment environment
                :key-name (-> config :bastion :key-name)})]

      ;; Create the encryption key for this domain and allow each service to use it, including the bastion
      (let [users (cons (ref-module-var ["bastion"] "role_arn")
                        (for [service (distinct (map :service (:services config)))]
                          (ref-module-var [service "security"] "role_arn")))]
        [(module ["kms-key"] :kms
                 {:root-arn (-> config :kms :root)
                  :admin-arns (-> config :kms :admins)
                  :user-arns users
                  :attachment-arns users})]))

     :resource
     { ;;Every service we run needs access to S3 to fetch releases:
      :aws-iam-role-policy
      (into {}
            (for [{:keys [service]} (:services config)]
              [(resolve-path [service "s3_jars"])
               {:name (str environment "-" service "-s3-jars")
                :role (ref-module-var [service "security"] "role_id")
                :policy (mach.core/json
                         {:Statement [{:Effect "Allow"
                                       :Action ["s3:GetObject"]
                                       :Resource [(str "arn:aws:s3:::" releases-bucket "/*")]}]})}]))}

     ;; Create run scripts for each service/version
     :data
     {:template-file
      (into {}
            (for [{:keys [service version] :as service-m} (:services config)]
              [(resolve-path [service version "user-data"])
               {:template (str "${file(\"" roll-home "/tf/files/run-server.sh\")}")
                :vars {:launch_command (when (:launch-command-fn opts) ((:launch-command-fn opts) service-m config))
                       :release_artifact (when (:release-file-fn opts) ((:release-file-fn opts) service-m config))
                       :releases_bucket releases-bucket}}]))}}))
