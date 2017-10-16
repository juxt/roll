(ns roll.modules.asg
  (:require [roll.utils :refer [render-mustache ref-var]]))

(defmulti build-user-data (fn [config asg {:keys [template]}] template))

(defmethod build-user-data :default [config asg launch-config]
  (let [releases-bucket (-> config :releases-bucket)
        release-artifact (-> asg :release-artifact)
        launch-command (-> launch-config :args :launch-command)]

    ;; Todo move to spec:
    (assert releases-bucket "releases-bucket")
    (assert release-artifact "release-artifact")
    (assert launch-command "Launch command")

    (render-mustache {:releases-bucket releases-bucket
                      :release-artifact release-artifact
                      :launch-command launch-command}
                     "files/run-server.sh")))

(defmethod build-user-data :java8 [config asg launch-config]
  (let [release-artifact (-> asg :release-artifact)
        jvm-opts (-> launch-config :args :jvm-opts)
        cmd (str "java " (clojure.string/join " " jvm-opts) " -jar " release-artifact)]

    (build-user-data config asg (-> launch-config
                                    (assoc :template :default)
                                    (assoc-in [:args :launch-command] cmd)))))

(defn- launch-configurations [{:keys [environment releases-bucket] :as config}]
  (into {}
        (for [{:keys [service version load-balancer release-artifact] :as asg} (:asgs config)
              :let [{:keys [instance-type ami key-name launch-config user-data]} (-> config :services service)]]
          [(str environment "-" (name service) "-" version)
           {:name-prefix environment
            :image-id ami
            :instance-type instance-type
            :security-groups [(ref-var [:aws-security-group service :id])]
            :iam-instance-profile (ref-var [:aws-iam-instance-profile service :name])
            :user-data (or user-data
                           (build-user-data config asg launch-config))
            :key-name key-name
            :lifecycle {:create-before-destroy true}}])))

(defn- auto-scaling-groups [{:keys [environment] :as config}]
  (into {}
        (for [{:keys [service version load-balancer] :as m} (:asgs config)
              :let [{:keys [instance-count target-group-arns availability-zones] :or {instance-count 2
                                                                                      target-group-arns []}}
                    (-> config :services service)
                    asg-name (str environment "-" (name service) "-" version)]]
          [asg-name
           (merge
            {:availability-zones    availability-zones
             :name                  asg-name
             :max-size              (str instance-count)
             :min-size              (str instance-count)
             :launch-configuration  (ref-var [:aws-launch-configuration asg-name :name])

             :tag [{:key "Name"
                    :value asg-name
                    :propagate_at_launch true}]

             :lifecycle {:create_before_destroy true}}
            (when load-balancer
              {:target-group-arns [(ref-var [:aws-alb-target-group load-balancer :arn])]}))])))

(defn generate [config]
  (when (not-empty (:asgs config))
    {:resource
     {:aws-launch-configuration (launch-configurations config)
      :aws-autoscaling-group (auto-scaling-groups config)}}))
