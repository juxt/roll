(ns roll.modules.vpc
  (:require [roll.utils :refer [ref-var]]))

;; TODO: Strong argument for simplicity sake to move availability
;; zones out of the individual service config and into the top
;; level. We could still offer the ability to change it on a per
;; service basis.

(defn generate
  "If no VPC is specified, we need to create one."
  [{:keys [environment vpc-id availability-zones subnets subnet-ids] :as config}]
  (let [availability-zones (sort (distinct (mapcat :availability-zones (vals (:services config)))))]
    (if vpc-id
      {:locals
       {:vpc-id vpc-id
        :subnet-ids subnet-ids}}
      {:locals
       {:vpc-id (ref-var [:aws-vpc :main :id])
        :subnet-ids (mapv #(ref-var [:aws-subnet % :id]) (keys subnets))}
       :resource
       {:aws-vpc
        {:main
         {:cidr_block "10.0.0.0/16"
          :tags {:Name environment}
          :enable-dns-support true
          :enable-dns-hostnames true}}

        :aws-internet-gateway
        {:main
         {:vpc-id (ref-var [:aws-vpc :main :id])
          :tags {:Name environment}}}

        ;; For each AZ create a subnet:
        :aws-subnet
        (into {}
              (for [[k {:keys [availability-zone index]}] subnets]
                [k
                 {:vpc-id (ref-var [:aws-vpc :main :id])
                  :availability-zone availability-zone
                  :cidr_block (str "10.0." (+ 100 index) ".0/24")
                  :tags {:Name (name k)}}]))

        ;; For each AZ create a route table:
        :aws-route-table
        (into {}
              (for [[k {:keys [availability-zone]}] subnets]
                [k
                 {:vpc-id (ref-var [:aws-vpc :main :id])
                  :tags {:Name (name k)}}]))

        ;; Associate route tables with subnets
        :aws-route-table-association
        (into {}
              (for [[k {:keys [availability-zone]}] subnets]
                [k
                 {:subnet-id (ref-var [:aws-subnet k :id])
                  :route-table-id (ref-var [:aws-route-table k :id])}]))

        ;; Hook each route table up to the internet gateway using a route:
        :aws-route
        (into {}
              (for [[k {:keys [availability-zone]}] subnets]
                [k
                 {:route-table-id (ref-var [:aws-route-table k :id])
                  ;;                  :depends-on ["aws_route_table.public"]
                  :destination-cidr-block "0.0.0.0/0"
                  :gateway-id (ref-var [:aws-internet-gateway :main :id])}]))}})))
