(ns roll.modules.service-security
  (:require [roll.utils :refer [resolve-path ->json ref-var ->snake]]))

(defn- aws-security-groups
  "Generate a security group for each service."
  [{:keys [environment] :as config}]
  (into {} (for [service (keys (:services config))
                 :let [environment (str environment "-" (name service))]]
             [service
              {:name environment
               :description (str "Allow access to " environment" application")

               :ingress [{:from-port 22
                          :to-port 22
                          :protocol "tcp"
                          :cidr-blocks ["0.0.0.0/0"]}
                         {:from-port 8080
                          :to-port 8080
                          :protocol "tcp"
                          :cidr-blocks ["0.0.0.0/0"]}]

               :egress [{:from-port 0
                         :to-port 65535
                         :protocol "tcp"
                         :cidr-blocks ["0.0.0.0/0"]}]

               :tags {:name environment}}])))

(def default-iam-policy
  {:Version "2012-10-17"
   :Statement [{:Sid ""
                :Action "sts:AssumeRole"
                :Effect "Allow"
                :Principal {:Service "ec2.amazonaws.com"}}]})

(defn- aws-iam-roles
  "Generate an IAM role for each service."
  [{:keys [environment] :as config}]
  (into {} (for [service (keys (:services config))
                 :let [environment (str environment "-" (name service))]]
             [service
              {:name (->snake (str environment"_role"))
               :assume-role-policy (->json default-iam-policy)}])))

(defn- aws-iam-instance-profile
  [{:keys [environment] :as config}]
  (into {} (for [service (keys (:services config))
                 :let [environment (str environment "-" (name service))]]
             [service
              {:name environment
               :role (ref-var [:aws-iam-role service :name])}])))

(defn- aws-iam-role-policies
  "Every service we run needs access to S3 to fetch releases, plus
  additional policies as described."
  [{:keys [releases-bucket environment services]}]
  (into {} (for [[service {:keys [policies]}] services]
             [service
              {:name (str environment "-" (name service))
               :role (ref-var [:aws-iam-role service :id])
               :policy (->json
                        {:Statement
                         (concat [{:Effect "Allow"
                                   :Action ["s3:GetObject"]
                                   :Resource [(str "arn:aws:s3:::" releases-bucket "/*")]}]
                                 policies)})}])))

(defn generate [{:keys [environment] :as config}]
  {:resource
   {:aws-security-group (aws-security-groups config)
    :aws-iam-role (aws-iam-roles config)
    :aws-iam-instance-profile (aws-iam-instance-profile config)
    :aws-iam-role-policy (aws-iam-role-policies config)}})
