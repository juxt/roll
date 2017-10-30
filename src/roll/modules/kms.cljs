(ns roll.modules.kms
  (:require [roll.utils :refer [$]]))

(defn- iam-policy [{:keys [root-arn admin-arns user-arns attachment-arns]}]
  {:statement [{:sid "Enable IAM User Permissions"
                :effect "Allow"
                :principals [{:type "AWS" :identifiers [root-arn]}]
                :actions ["kms:*"]
                :resources ["*"]}
               {:sid "Allow access for Key Administrators"
                :effect "Allow"
                :principals [{:type "AWS" :identifiers admin-arns}]
                :actions ["kms:Create*"
                          "kms:Describe*"
                          "kms:Enable*"
                          "kms:List*"
                          "kms:Put*"
                          "kms:Update*"
                          "kms:Revoke*"
                          "kms:Disable*"
                          "kms:Get*"
                          "kms:Delete*"
                          "kms:ScheduleKeyDeletion"
                          "kms:CancelKeyDeletion"]
                :resources ["*"]}
               {:sid "Allow use of the key"
                :effect "Allow"
                :principals [{:type "AWS" :identifiers user-arns}]
                :actions ["kms:Encrypt"
                          "kms:Decrypt"
                          "kms:ReEncrypt*"
                          "kms:GenerateDataKey*"
                          "kms:DescribeKey"]
                :resources ["*"]}
               {:sid "Allow attachment of persistent resources"
                :effect "Allow"
                :principals [{:type "AWS" :identifiers attachment-arns}]
                :actions ["kms:CreateGrant"
                          "kms:ListGrants"
                          "kms:RevokeGrant"]
                :resources ["*"]
                :condition {:test "Bool"
                            :variable "kms:GrantIsForAWSResource"
                            :values ["true"]}}]})

(defn- generate-policy [config]
  (when (:kms config)
    (let [users (cons ($ [:aws-iam-role :bastion :arn])
                      (for [service (keys (:services config))]
                        ($ [:aws-iam-role service :arn])))]
      {:aws-iam-policy-document {:kms-policy (iam-policy {:root-arn (-> config :kms :root)
                                                          :admin-arns (-> config :kms :admins)
                                                          :user-arns users
                                                          :attachment-arns users})}})))

(defn generate
  "Create the encryption key allow each service to use it, including
  the bastion."
  [{:keys [environment] :as config}]
  (when (:kms config)
    {:data
     (generate-policy config)
     :resource
     {:aws-kms-alias {:alias {:name (str "alias/" environment)
                              :target-key-id ($ [:aws-kms-key :default :key-id])}}
      :aws-kms-key {:default {:description "Key"
                              :deletion_window_in_days 20
                              :policy ($ [:data :aws-iam-policy-document :kms-policy :json])}}}}))
