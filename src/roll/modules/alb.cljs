(ns roll.modules.alb
  (:require [roll.utils :refer [resolve-path $]]))

(defn- listeners [config]
  (into {}
        (for [[balancer listeners] (:load-balancers config)
              [i {:keys [listen protocol ssl-policy certificate-arn]}] (map-indexed vector listeners)]
          [(resolve-path [balancer (str i)])
           {:load-balancer-arn ($ [:aws-alb balancer :arn])
            :port listen
            :protocol protocol
            :ssl-policy ssl-policy
            :certificate-arn certificate-arn
            :default-action {:target-group-arn ($ [:aws-alb-target-group balancer :arn])
                             :type "forward"}}])))

(defn- security-groups
  "Every ALB needs its own security group"
  [{:keys [environment load-balancers]}]
  (into {}
        (for [[balancer listeners] load-balancers
              port (map :listen listeners)]
          [(resolve-path [balancer (str port)])
           {:name (str environment "-" (name balancer) "-" port "-alb-sg")
            :description "controls access to the application load balancer"
            :vpc-id ($ [:local :vpc-id])
            :ingress {:protocol    "tcp"
                      :from_port   port
                      :to_port     port
                      :cidr_blocks ["0.0.0.0/0"]}
            :egress {:protocol    "-1"
                     :from_port   0
                     :to_port     0
                     :cidr_blocks ["0.0.0.0/0"]}}])))

(defn- target-groups [{:keys [environment load-balancers]}]
  (into {}
        (for [[balancer listeners] load-balancers
              [i {:keys [forward protocol] :or {forward 8080
                                                protocol "HTTP"}}]
              (map-indexed vector listeners)]
          [(name balancer)
           {:name (str environment "-" (name balancer) "-" i "-alb-tg")
            :port forward
            :protocol protocol
            :vpc-id ($ [:local :vpc-id])}])))

(defn albs [{:keys [load-balancers environment] :as config}]
  (into {}
        (for [[balancer listeners] load-balancers
              :let [listen-ports (map :listen listeners)]]
          [(name balancer)
           {:name (str environment "-" (name balancer) "-alb")
            :internal false
            :security-groups (map (fn [port] ($ [:aws-security-group (resolve-path [balancer (str port)]) :id])) listen-ports)
            :subnets [($ [:local :subnet-ids])]
            :enable-deletion-protection false}])))

(defn generate [config]
  (when (not-empty (:load-balancers config))
    {:resource
     {:aws-security-group (security-groups config)
      :aws-alb (albs config)
      :aws-alb-listener (listeners config)
      :aws-alb-target-group (target-groups config)}
     :output
     (into {}
           (for [[balancer listeners] (:load-balancers config)
                 :let [listen-ports (map :listen listeners)]]
             [(str (name balancer) "-alb-dns")
              {:value ($ [:aws-alb (name balancer) :dns-name])}]))}))
