(ns inferenceql.inference.gpm.crosscat
  (:require [clojure.data :refer [diff]]
            [inferenceql.inference.gpm.view :as view]
            [inferenceql.inference.gpm.column :as column]
            [inferenceql.inference.gpm.primitive-gpms :as pgpms]
            [inferenceql.inference.primitives :as primitives]
            [inferenceql.inference.gpm.proto :as gpm.proto]))

(defn update-hyper-grids
  "Given a collection of columns, updates the columns' respective hyper grids."
  [columns]
    (reduce-kv (fn [acc col-name col]
              (let [hyper-grid (pgpms/hyper-grid (:stattype col) (vals (:data col)))]
                (-> acc
                    (assoc col-name (-> col
                                        (assoc :hyper-grid hyper-grid)
                                        (column/update-hypers))))))
            {}
            columns))

(defn generate-view-latents
  "Given a CrossCat model, samples view assignments parameterized by the
  current concentration parameter value."
  [n alpha]
  (let [[_ assignments] (primitives/crp-simulate n {:alpha alpha})]
        (zipmap (range) (shuffle assignments))))

(defrecord XCat [views latents]
  gpm.proto/GPM
  (logpdf [this targets constraints]
    (let [[_ _ intersection] (diff (set (keys targets)) (set (keys constraints)))]
      (if (not-empty intersection)
        (throw (ex-info (str "Targets and constraints must be unique! "
                             "These are shared: "
                             (seq intersection))
                        {:targets targets :constraints constraints}))
        (reduce-kv (fn [logp _ view]
                     ;; Filtering view variables happens naturally.
                     (let [view-logp (gpm.proto/logpdf view targets constraints)]
                       (+ logp view-logp)))
                   0
                   views))))
  (simulate [this targets constraints]
    (let [[_ _ intersection] (diff (set targets) (set (keys constraints)))]
      (if (not-empty intersection)
        (throw (ex-info (str "Targets and constraints must be unique! "
                             "These are shared: "
                             (seq intersection))
                        {:targets targets :constraints constraints}))
        (->> views
             (map (fn [[_ view]]
                    (gpm.proto/simulate view targets constraints)))
             (filter not-empty)
             (apply merge)))))
  gpm.proto/Incorporate
  (incorporate [this x]
    (let [row-id (gensym)]
      (-> this
          ((fn [gpm]
             ;; Incorporate correct variables within the datumn into their respective views.
             (reduce-kv (fn [m view-idx view]
                          (let [view-vars (keys (:columns view))
                                x-view (select-keys x view-vars)]
                            (update-in m [:views view-idx] #(-> (view/incorporate-by-rowid % x-view row-id)
                                                                (update :columns update-hyper-grids)))))
                        gpm
                        views))))))
  (unincorporate [this x]
    (-> this
        ((fn [xcat]
           ;; Unincorporate correct variables within the datumn from one of their associated views.
           (reduce-kv (fn [m view-name view]
                        (let [view-vars (keys (:columns view))
                              x-view (select-keys x view-vars)]
                          (update-in m [:views view-name] #(-> (gpm.proto/unincorporate % x-view)
                                                               (update :columns update-hyper-grids)))))
                      xcat
                      views)))))
  gpm.proto/Score
  (logpdf-score [this]
    (reduce (fn [acc [_ view]]
              (+ acc (gpm.proto/logpdf-score view)))
            0
            views)))

(defn incorporate-column
  "Incorporates a column in to the model at the specified view."
  [xcat column view-assignment]
  (let [var-name (:var-name column)]
    (-> xcat
        ;; Update sufficient statistics of the XCat CRP.
        (assoc-in [:latents :z var-name] view-assignment)
        (update-in [:latents :counts view-assignment] inc)
        ;; Incorporate the column to the correct view.
        (update-in [:views view-assignment] #(view/incorporate-column % column)))))

(defn unincorporate-column
  "Unincorporates a column from the model with the specified name."
  [xcat var-name]
  (let [z (-> xcat :latents :z (get var-name))]
    (-> xcat
        ;; Update sufficient statistics of the XCat CRP.
        (update-in [:latents :z] dissoc var-name)
        (update-in [:latents :counts z] dec)
        ;; Unincorporate the column from the associated view.
        (update-in [:views z] #(view/unincorporate-column % var-name)))))

(defn filter-empty-views
  "Filters empty views from a CrossCat model."
  [xcat]
  (let [latents (:latents xcat)
        views-to-remove (keys (filter #(zero? (second %)) (:counts latents)))]
    (-> xcat
        (update :views #(apply dissoc % views-to-remove))
        (update-in [:latents :counts] #(apply dissoc % views-to-remove)))))

(defn view-logpdf-scores
  "Given an XCat GPM and a Column, calculates the logpdf-score of the Column GPM
  if it were to be incorporated into each of the constituent View GPMs."
  [xcat column]
  (reduce-kv (fn [scores view-name view]
               (let [view-latents (:latents view)]
                 (assoc scores
                        view-name
                        (gpm.proto/logpdf-score (column/update-column column view-latents)))))
             {}
             (:views xcat)))

(defn generate-view
  "Generates an empty view with latent assignments generated from a CRP
  with concentration parameter alpha."
  [row-ids]
  (let [n (count row-ids)
        alpha (primitives/simulate :gamma {:k 1 :theta 1})
        [table-counts assignments] (primitives/crp-simulate n {:alpha alpha})
        counts (zipmap (range) table-counts)
        y (zipmap row-ids assignments)
        latents {:alpha alpha
                 :counts counts
                 :y y}
        columns {}
        assignments {}]
    (view/->View columns latents assignments)))

(defn add-aux-views
  "Add m auxiliary Views to the given XCat GPM."
  [xcat m]
  (let [row-ids (-> xcat :views first second :latents :y keys)]
    (reduce (fn [xcat' _]
              (let [view-symbol (gensym)]
                (-> xcat'
                    (assoc-in [:latents :counts view-symbol] 0)
                    (assoc-in [:views view-symbol] (generate-view row-ids)))))
            xcat
            (range m))))

(defn construct-xcat-from-latents
  "Constructor for a View GPM, given a spec for the View, latent
  assignments of data to their respective categories, statistical types of the columns,
  and data. Used in CrossCat inference.

  spec: a View specification, defined as map of {var-name var-hypers}.
  types: the statistical types of the variables contained in the columns (e.g. :bernoulli).
  latents: a map of the below structure, used to keep track of row-category assignments,
           as well as category sufficient statistics:
             {:alpha  number                     The concentration parameter for the Column's CRP
              :counts {category-name count}      Maps category name to size of the category. Updated
                                                 incrementally instead of being calculated on the fly.
              :y {row-identifier category-name}  Maps rows to their current category assignment.
  data: the data belonging to the Column. Must be a map of {row-id {var-name value}},
        including nil values.
  options (optional): Information needed in the column; e.g. For a :categorical Column,
                      `options` would contain a list of possible values the variable could take."
  ([spec latents data]
   (construct-xcat-from-latents spec latents data {:options {}}))
  ([spec latents data {:keys [options]}]
   (let [views (:views spec)
         types (:types spec)
         global-latents (:global latents)
         ;; Create views with correctly populated categories.
         views' (reduce-kv (fn [acc view-name view]
                             (let [view-vars (-> view :hypers keys)]
                               (assoc acc view-name (view/construct-view-from-latents
                                                      view
                                                      (get-in latents [:local view-name])
                                                      types
                                                      ;; Need to filter each datum for view-specific
                                                      ;; variables in order to avoid error.
                                                      (reduce-kv (fn [data' row-id datum]
                                                                   (assoc data' row-id (select-keys datum view-vars)))
                                                                 {}
                                                                 data)
                                                      {:options options
                                                       :crosscat true}))))
                           {}
                           views)
         ;; Create unordered (bag) for latent counts and column view assignments.
         xcat-latents (reduce-kv (fn [m view-idx _]
                                   (let [var-names (keys (:hypers (get views view-idx)))]
                                     (-> m
                                         (assoc-in [:counts view-idx] (count var-names))
                                         (update :z #(reduce (fn [acc var-name]
                                                               (assoc acc var-name view-idx))
                                                             %
                                                             var-names)))))
                                 {:alpha (:alpha global-latents)
                                  :counts {}
                                  :z {}}
                                 views')]
     (->XCat views' xcat-latents))))

(defn construct-xcat-from-hypers
  "Constructor of a XCat GPM, given a specification for variable hyperparameters, as well
  as variable statistical types."
  [spec]
  (let [latents {:global {:alpha 1 :counts {} :z {}}
                 :local {0 {:alpha 1
                            :counts {}
                            :y {}}}}
        options (:options spec)
        data {}]
    (construct-xcat-from-latents spec latents data {:options options})))

(defn xcat?
  "Checks if the given GPM is an XCat GPM."
  [stattype]
  (and (record? stattype)
       (instance? XCat stattype)))
