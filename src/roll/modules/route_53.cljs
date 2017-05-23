(ns roll.modules.route-53
  (:require [roll.utils :refer [tf-path-to ref-var]]
            [clojure.string]))

(defn generate
  "Create Alias Resource Record Sets"
  [config]
  (when (not-empty (:route-53-aliases config))
    {:resource
     {:aws-route53-record
      (into {}
            (for [{:keys [resource-name name-prefix zone-id load-balancer]} (:route-53-aliases config)
                  :let [_ (assert (get-in config [:load-balancers load-balancer]))]]
              [(or resource-name (clojure.string/replace name-prefix #"\." "_"))
               {:zone-id zone-id
                :name name-prefix
                :type "A"
                :alias { ;;https://github.com/hashicorp/terraform/issues/10869 for why we do lower
                        :name (str "${lower(" (tf-path-to [:aws-alb load-balancer :dns-name]) ")}")
                        :zone-id (ref-var [:aws-alb load-balancer :zone-id])
                        :evaluate-target-health false}}]))}}))
