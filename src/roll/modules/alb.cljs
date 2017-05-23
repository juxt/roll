(ns roll.modules.alb
  (:require [roll.utils :refer [resolve-path ref-var]]))

(defn- listeners [config]
  (into {}
        (for [[balancer listeners] (:load-balancers config)
              [i {:keys [listen protocol ssl-policy certificate-arn]}] (map-indexed vector listeners)]
          [(resolve-path [balancer (str i)])
           {:load-balancer-arn (ref-var [:aws-alb balancer :arn])
            :port listen
            :protocol protocol
            :ssl-policy ssl-policy
            :certificate-arn certificate-arn
            :default-action {:target-group-arn (ref-var [:aws-alb-target-group balancer :arn])
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
            :ingress {:protocol    "tcp"
                      :from_port   port
                      :to_port     port
                      :cidr_blocks ["0.0.0.0/0"]}
            :egress {:protocol    "-1"
                     :from_port   0
                     :to_port     0
                     :cidr_blocks ["0.0.0.0/0"]}}])))

(defn- target-groups [{:keys [environment forward-port protocol vpc-id load-balancers]
                       :or {forward-port 8080
                            protocol "HTTP"}}]
  (into {}
        (for [[balancer listeners] load-balancers]
          [(name balancer)
           {:name (str environment "-" (name balancer) "-alb-tg")
            :port forward-port
            :protocol protocol
            :vpc-id vpc-id}])))

(defn albs [{:keys [load-balancers environment subnets]}]
  (into {}
        (for [[balancer listeners] load-balancers
              :let [listen-ports (map :listen listeners)]]
          [(name balancer)
           {:name (str environment "-" (name balancer) "-alb")
            :internal false
            :security-groups (map (fn [port] (ref-var [:aws-security-group (resolve-path [balancer (str port)]) :id])) listen-ports)
            :subnets subnets
            :enable-deletion-protection false}])))

(defn generate [config]
  (when (not-empty (:load-balancers config))
    {:resource
     {:aws-security-group (security-groups config)
      :aws-alb (albs config)
      :aws-alb-listener (listeners config)
      :aws-alb-target-group (target-groups config)}}))
