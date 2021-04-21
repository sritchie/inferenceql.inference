(ns inferenceql.inference.gpm.constrained
  (:refer-clojure :exclude [eval])
  (:require [inferenceql.inference.gpm.proto :as gpm.proto]
            [net.cgrand.xforms.rfs :as rfs]))

(defn ^:private and-f
  "Like `clojure.core/and`, but is a function and thus evaluates its arguments
  eagerly."
  [& xs]
  (every? identity xs))

(defn ^:private or-f
  "Like `clojure.core/or`, but is a function and thus evaluates its arguments
  eagerly."
  [& xs]
  (boolean (some #{true} (map boolean xs))))

(def ^:private operator-env
  "A map from operator symbols to operator functions."
  {'< <
   '<= <=
   '= =
   '> >
   '>= >=
   'and and-f
   'or or-f
   'not not})

(defn ^:private event->pred
  "Returns a predicate for an event. The predicate when called on a sample will
  return true if the event has occurred for that sample and false otherwise."
  [event {:keys [operation? variable? operands operator]}]
  (fn [env]
    (let [eval (fn eval [node env]
                 (cond (operation? node)
                       (let [f (get operator-env (operator node))
                             args (map #(eval % env) (operands node))]
                         (apply f args))

                       (variable? node)
                       (if-some [v (get env node)]
                         v
                         (throw (ex-info "could not resolve symbol"
                                         {:symbol node :env env})))

                       :else node))]
      (eval event env))))

(defn ^:private event->variables
  "Returns all the variables in an event."
  [event {:keys [operation? variable? operands]}]
  (into #{}
        (filter variable?)
        (tree-seq operation? operands event)))

(defrecord ConstrainedGPM [gpm pred? variables sample-size]
  gpm.proto/GPM
  (logpdf [this targets conditions]
    (Math/log
     (transduce (map #(let [conditions (merge conditions %)]
                        (Math/exp (gpm.proto/logpdf gpm targets conditions))))
                rfs/avg
                (repeatedly sample-size #(gpm.proto/simulate this variables {})))))

  (simulate [_ targets conditions]
    (loop []
      (let [v (gpm.proto/simulate gpm (into targets variables) conditions)]
        (if (pred? v)
          (select-keys v targets)
          (recur)))))

  gpm.proto/Variables
  (variables [_]
    (gpm.proto/variables gpm)))

(defn constrain
  "Constrains gpm based on event via rejection sampling. Arguments are the same
  as those for `inferenceql.inference.gpm/constrain`."
  [gpm event opts]
  (let [pred? (event->pred event opts)
        variables (event->variables event opts)]
    (map->ConstrainedGPM {:gpm gpm
                          :pred? pred?
                          :variables variables
                          :sample-size 1000})))
