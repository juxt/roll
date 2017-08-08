(ns roll.modules.asg
  (:require [roll.utils :refer [render-mustache ref-var]]))

(defn- launch-configurations [{:keys [environment releases-bucket] :as config}]
  (into {}
        (for [{:keys [service version load-balancer launch-command release-artifact] :as m} (:asgs config)
              :let [{:keys [instance-type ami key-name]} (-> config :services service)]]
          [(str environment "-" (name service) "-" version)
           {:name-prefix environment
            :image-id ami
            :instance-type instance-type
            :security-groups [(ref-var [:aws-security-group service :id])]
            :iam-instance-profile (ref-var [:aws-iam-instance-profile service :name])
            :user-data (-> m
                           (select-keys [:launch-command :release-artifact])
                           (assoc :releases-bucket releases-bucket)
                           (render-mustache "files/run-server.sh"))
            :key-name key-name
            :lifecycle {:create-before-destroy false}}])))

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
