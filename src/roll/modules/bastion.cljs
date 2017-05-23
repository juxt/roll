(ns roll.modules.bastion
  (:require [roll.utils :refer [resolve-path ->json ref-var]]))

(defn- aws-security-group
  "Generate a security group for the bastion."
  [{:keys [environment]}]
  {:bastion
   {:name (str environment "-bastion-sg")
    :description (str "Allow access to " environment "-bastion application")

    :ingress [{:from-port 22
               :to-port 22
               :protocol "tcp"
               :cidr-blocks ["0.0.0.0/0"]}]

    :egress [{:from-port 0
              :to-port 65535
              :protocol "tcp"
              :cidr-blocks ["0.0.0.0/0"]}]

    :tags {:name (str environment "-bastion")}}})

(def default-iam-policy
  {:version "2012-10-17"
   :statement [{:sid ""
                :action "sts:AssumeRole"
                :effect "Allow"
                :principal {:Service "ec2.amazonaws.com"}}]})

(defn- aws-iam-role
  "Generate an IAM role for each service."
  [{:keys [environment] :as config}]
  {:bastion {:name (str environment"-bastion-role")
             :assume-role-policy (->json default-iam-policy)}})

(defn- aws-iam-instance-profile [{:keys [environment] :as config}]
  {:bastion
   {:name (str environment "-bastion")
    :roles [(ref-var [:aws-iam-role :bastion :name])]}})

(defn- aws-instance [{:keys [environment] :as config}]
  {:bastion
   {:ami "ami-0c391c6a"
    :instance-type "t2.medium"
    :availability-zone "eu-west-1a"
    :iam-instance-profile (ref-var [:aws-iam-instance-profile :bastion :name])
    :associate-public-ip-address true
    :vpc_security_group_ids [(ref-var [:aws-security-group :bastion :id])]
    :key-name (-> config :bastion :key-name)
    :user-data (or (-> config :bastion :user-data) "")
    :tags [{:Name (str environment "-bastion")
            :Type "Bastion"}]}})

(defn generate [config]
  (when (:bastion config)
    {:resource
     {:aws-instance (aws-instance config)
      :aws-iam-role (aws-iam-role config)
      :aws-iam-instance-profile (aws-iam-instance-profile config)
      :aws-security-group (aws-security-group config)}}))
