(ns metabase.mbql.normalize
  "Logic for taking any sort of weird MBQL query and normalizing it into a standardized, canonical form. You can think
  of this like taking any 'valid' MBQL query and rewriting it as-if it was written in perfect up-to-date MBQL in the
  latest version. There are four main things done here, done as four separate steps:

  #### NORMALIZING TOKENS

  Converting all identifiers to lower-case, lisp-case keywords. e.g. `{\"SOURCE_TABLE\" 10}` becomes `{:source-table
  10}`.

  #### CANONICALIZING THE QUERY

  Rewriting deprecated MBQL 95/98 syntax and other things that are still supported for backwards-compatibility in
  canonical MBQL 2000 syntax. For example `{:breakout [:count 10]}` becomes `{:breakout [[:count [:field-id 10]]]}`.

  #### WHOLE-QUERY TRANSFORMATIONS

  Transformations and cleanup of the query structure as a whole to fix inconsistencies. Whereas the canonicalization
  phase operates on a lower-level, transforming invidual clauses, this phase focuses on transformations that affect
  multiple clauses, such as removing duplicate references to Fields if they are specified in both the `:breakout` and
  `:fields` clauses.

  This is not the only place that does such transformations; several pieces of QP middleware perform similar
  individual transformations, such as `reconcile-breakout-and-order-by-bucketing`.

  #### REMOVING EMPTY CLAUSES

  Removing empty clauses like `{:aggregation nil}` or `{:breakout []}`.

  Token normalization occurs first, followed by canonicalization, followed by removing empty clauses."
  (:require [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [medley.core :as m]
            [metabase.common.i18n :refer [tru]]
            [metabase.mbql
             [predicates :as mbql.pred]
             [util :as mbql.u]]))

(defn- mbql-clause?
  "True if `x` is an MBQL clause (a sequence with a token as its first arg). (This is different from the implementation
  in `mbql.u` because it also supports un-normalized clauses. You shouldn't need to use this outside of this
  namespace.)"
  [x]
  (and (sequential? x)
       ((some-fn keyword? string?) (first x))))

(defn is-clause?
  "If `x` an MBQL clause, and an instance of clauses defined by keyword(s) `k-or-ks`?

    (is-clause? :count [:count 10])        ; -> true
    (is-clause? #{:+ :- :* :/} [:+ 10 20]) ; -> true

  (This is different from the implementation in `mbql.u` because it also supports un-normalized clauses. You shouldn't
  need to use this outside of this namespace.)"
  [k-or-ks x]
  (and
   (mbql-clause? x)
   (let [clause-name (mbql.u/normalize-token (first x))]
     (if (coll? k-or-ks)
       ((set k-or-ks) clause-name)
       (= k-or-ks clause-name)))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                NORMALIZE TOKENS                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

(declare normalize-tokens)

(defn- normalize-expression-ref-tokens
  "For expression references (`[:expression \"my_expression\"]`) keep the arg as is but make sure it is a string."
  [_ expression-name]
  [:expression (mbql.u/qualified-name expression-name)])

(defn- normalize-binning-strategy-tokens
  "For `:binning-strategy` clauses (which wrap other Field clauses) normalize the strategy-name and recursively
  normalize the Field it bins."
  ([_ field strategy-name]
   [:binning-strategy (normalize-tokens field :ignore-path) (mbql.u/normalize-token strategy-name)])
  ([_ field strategy-name strategy-param]
   (conj (normalize-binning-strategy-tokens nil field strategy-name)
         strategy-param)))

(defn- normalize-field-literal-tokens
  "Similarly, for Field literals, keep the arg as-is, but make sure it is a string."
  [_ field-name field-type]
  [:field-literal (mbql.u/qualified-name field-name) (keyword field-type)])

(defn- normalize-datetime-field-tokens
  "Datetime fields look like `[:datetime-field <field> <unit>]` or `[:datetime-field <field> :as <unit>]`; normalize the
  unit, and `:as` (if present) tokens, and the Field."
  ([_ field unit]
   [:datetime-field (normalize-tokens field :ignore-path) (mbql.u/normalize-token unit)])
  ([_ field _ unit]
   [:datetime-field (normalize-tokens field :ignore-path) :as (mbql.u/normalize-token unit)]))

(defn- normalize-time-interval-tokens
  "`time-interval`'s `unit` should get normalized, and `amount` if it's not an integer."
  ([_ field amount unit]
   [:time-interval
    (normalize-tokens field :ignore-path)
    (if (integer? amount)
      amount
      (mbql.u/normalize-token amount))
    (mbql.u/normalize-token unit)])
  ([_ field amount unit options]
   (conj (normalize-time-interval-tokens nil field amount unit) (normalize-tokens options :ignore-path))))

(defn- normalize-relative-datetime-tokens
  "Normalize a `relative-datetime` clause. `relative-datetime` comes in two flavors:

     [:relative-datetime :current]
     [:relative-datetime -10 :day] ; amount & unit"
  ([clause _]
   [clause :current])
  ([clause amount unit]
   [clause amount (mbql.u/normalize-token unit)]))

(def ^:private mbql-clause->special-token-normalization-fn
  "Special fns to handle token normalization for different MBQL clauses."
  {:expression        normalize-expression-ref-tokens
   :field-literal     normalize-field-literal-tokens
   :datetime-field    normalize-datetime-field-tokens
   :binning-strategy  normalize-binning-strategy-tokens
   :time-interval     normalize-time-interval-tokens
   :relative-datetime normalize-relative-datetime-tokens
   :relative-datetime-padded normalize-relative-datetime-tokens})

(defn- normalize-mbql-clause-tokens
  "MBQL clauses by default get just the clause name normalized (e.g. `[\"COUNT\" ...]` becomes `[:count ...]`) and the
  args are left as-is. If we need to do something special on top of that implement a fn in
  `mbql-clause->special-token-normalization-fn` above to handle the special normalization rules"
  [[clause-name & args]]
  (let [clause-name (mbql.u/normalize-token clause-name)]
    (if-let [f (mbql-clause->special-token-normalization-fn clause-name)]
      (apply f clause-name args)
      (into [clause-name] (map #(normalize-tokens % :ignore-path) args)))))


(defn- aggregation-subclause? [x]
  (or (when ((some-fn keyword? string?) x)
        (#{:avg :count :cum-count :distinct :stddev :sum :min :max :+ :- :/ :*} (mbql.u/normalize-token x)))
      (when (mbql-clause? x)
        (aggregation-subclause? (first x)))))

(defn- normalize-ag-clause-tokens
  "For old-style aggregations like `{:aggregation :count}` make sure we normalize the ag type (`:count`). Other wacky
  clauses like `{:aggregation [:count :count]}` need to be handled as well :("
  [ag-clause]
  (cond
    ;; something like {:aggregations :count}
    ((some-fn keyword? string?) ag-clause)
    (mbql.u/normalize-token ag-clause)

    ;; named aggregation ([:named <ag> <name>])
    (is-clause? :named ag-clause)
    (let [[_ ag ag-name] ag-clause]
      [:named (normalize-ag-clause-tokens ag) ag-name])

    ;; something wack like {:aggregations [:count [:sum 10]]} or {:aggregations [:count :count]}
    (when (mbql-clause? ag-clause)
      (aggregation-subclause? (second ag-clause)))
    (mapv normalize-ag-clause-tokens ag-clause)

    :else
    (normalize-tokens ag-clause :ignore-path)))

(defn- normalize-expressions-tokens
  "For expressions, we don't want to normalize the name of the expression; keep that as is, but make it a keyword;
   normalize the definitions as normal."
  [expressions-clause]
  (into {} (for [[expression-name definition] expressions-clause]
             [(keyword expression-name)
              (normalize-tokens definition :ignore-path)])))

(defn- normalize-order-by-tokens
  "Normalize tokens in the order-by clause, which can have different syntax when using MBQL 95 or 98
  rules (`[<field> :asc]` vs `[:asc <field>]`, for example)."
  [clauses]
  (vec (for [subclause clauses]
         (if (mbql-clause? subclause)
           ;; MBQL 98+ [direction field] style: normalize as normal
           (normalize-mbql-clause-tokens subclause)
           ;; otherwise it's MBQL 95 [field direction] style: flip the args and *then* normalize the clause. And then
           ;; flip it back to put it back the way we found it.
           (reverse (normalize-mbql-clause-tokens (reverse subclause)))))))

(defn- normalize-template-tags
  "Normalize native-query template tags. Like `expressions` we want to preserve the original name rather than normalize
  it."
  [template-tags]
  (into {} (for [[tag-name tag-def] template-tags]
             [(mbql.u/qualified-name tag-name)
              (let [tag-def (-> (normalize-tokens tag-def :ignore-path)
                                (update :type mbql.u/normalize-token))]
                (cond-> tag-def
                  (:widget-type tag-def) (update :widget-type mbql.u/normalize-token)))])))

(defn- normalize-query-parameter [{:keys [type target], :as param}]
  (cond-> param
    ;; some things that get ran thru here, like dashcard param targets, do not have :type
    type   (update :type mbql.u/normalize-token)
    target (update :target #(normalize-tokens % :ignore-path))))

(defn- normalize-source-query [{native? :native, :as source-query}]
  (normalize-tokens source-query [(if native? :native :query)]))

(defn- normalize-join [join]
  ;; path in call to `normalize-tokens` is [:query] so it will normalize `:source-query` as appropriate
  (let [{:keys [strategy fields alias], :as join} (normalize-tokens join :query)]
    (cond-> join
      strategy
      (update :strategy mbql.u/normalize-token)

      ((some-fn keyword? string?) fields)
      (update :fields mbql.u/normalize-token)

      alias
      (update :alias mbql.u/qualified-name))))

(defn- normalize-source-metadata [metadata]
  (-> metadata
      (update :base_type    keyword)
      (update :special_type keyword)
      (update :fingerprint  walk/keywordize-keys)))

;; TODO - why not make this a multimethod of some sort?
(def ^:private path->special-token-normalization-fn
  "Map of special functions that should be used to perform token normalization for a given path. For example, the
  `:expressions` key in an MBQL query should preserve the case of the expression names; this custom behavior is
  defined below."
  {:type            mbql.u/normalize-token
   ;; don't normalize native queries
   :native          {:query         identity
                     :template-tags normalize-template-tags}
   :query           {:aggregation     normalize-ag-clause-tokens
                     :expressions     normalize-expressions-tokens
                     :order-by        normalize-order-by-tokens
                     :source-query    normalize-source-query
                     :source-metadata {::sequence normalize-source-metadata}
                     :joins           {::sequence normalize-join}}
   :parameters      {::sequence normalize-query-parameter}
   :context         #(some-> % mbql.u/normalize-token)
   :source-metadata {::sequence normalize-source-metadata}})

(defn normalize-tokens
  "Recursively normalize tokens in `x`.

  Every time this function recurses (thru a map value) it adds a new (normalized) key to key path, e.g. `path` will be
  `[:query :order-by]` when we're in the MBQL order-by clause. If we need to handle these top-level clauses in special
  ways add a function to `path->special-token-normalization-fn` above.

  In some cases, dealing with the path isn't desirable, but we don't want to accidentally trigger normalization
  functions (such as accidentally normalizing the `:type` key in something other than the top-level of the query), so
  by convention please pass `:ignore-path` to avoid accidentally triggering path functions."
  [x & [path]]
  (let [path       (if (keyword? path)
                     [path]
                     (vec path))
        special-fn (when (seq path)
                     (get-in path->special-token-normalization-fn path))]
    (cond
      (fn? special-fn)
      (special-fn x)

      ;; Skip record types because this query is an `expanded` query, which is not going to play nice here. Hopefully we
      ;; can remove expanded queries entirely soon.
      (record? x)
      x

      ;; maps should just get the keys normalized and then recursively call normalize-tokens on the values.
      ;; Each recursive call appends to the keypath above so we can handle top-level clauses in a special way if needed
      (map? x)
      (into {} (for [[k v] x
                     :let  [k (mbql.u/normalize-token k)]]
                 [k (normalize-tokens v (conj (vec path) k))]))

      ;; MBQL clauses handled above because of special cases
      (mbql-clause? x)
      (normalize-mbql-clause-tokens x)

      ;; for non-mbql sequential collections (probably something like the subclauses of :order-by or something like
      ;; that) recurse on all the args.
      ;;
      ;; To signify that we're recursing into a sequential collection, this appends `::sequence` to path
      (sequential? x)
      (mapv #(normalize-tokens % (conj (vec path) ::sequence)) x)

      :else
      x)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  CANONICALIZE                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

(declare canonicalize-mbql-clauses)

(defn- wrap-implicit-field-id
  "Wrap raw integer Field IDs (i.e., those in an implicit 'field' position) in a `:field-id` clause if they're not
  already. Done for MBQL 95 backwards-compatibility. e.g.:

    {:filter [:= 10 20]} ; -> {:filter [:= [:field-id 10] 20]}"
  [field]
  (if (integer? field)
    [:field-id field]
    field))

(defn- canonicalize-filter [filter-clause]
  (mbql.u/replace filter-clause
    seq? (recur (vec &match))

    ;; for `and`/`or`/`not` compound filters, recurse on the arg(s), then simplify the whole thing
    [(filter-name :guard #{:and :or :not}) & args]
    (mbql.u/simplify-compound-filter (vec (cons
                                           filter-name
                                           ;; we need to canonicalize any other mbql clauses that might show up in
                                           ;; args like datetime-field here because simplify-compund-filter validates
                                           ;; its output :(
                                           (map (comp canonicalize-mbql-clauses canonicalize-filter)
                                                args))))

    [(filter-name :guard #{:starts-with :ends-with :contains :does-not-contain}) field arg options]
    [filter-name (wrap-implicit-field-id field) arg options]

    [(filter-name :guard #{:starts-with :ends-with :contains :does-not-contain}) field arg]
    [filter-name (wrap-implicit-field-id field) arg]

    [:inside field-1 field-2 & coordinates]
    (vec
     (concat
      [:inside (wrap-implicit-field-id field-1) (wrap-implicit-field-id field-2)]
      coordinates))

    ;; if you put a `:datetime-field` inside a `:time-interval` we should fix it for you
    [:time-interval [:datetime-field field _] & args]
    (recur (apply vector :time-interval field args))

    ;; all the other filter types have an implict field ID for the first arg
    ;; (e.g. [:= 10 20] gets canonicalized to [:= [:field-id 10] 20]
    [(filter-name :guard #{:= :!= :< :<= :> :>= :is-null :not-null :between :inside :time-interval}) field & more]
    (apply vector filter-name (wrap-implicit-field-id field) more)

    ;; don't wrap segment IDs in `:field-id`
    [:segment _]
    &match
    _
    (throw (IllegalArgumentException. (str (tru "Illegal filter clause: {0}" filter-clause))))))

(defn- canonicalize-aggregation-subclause
  "Remove `:rows` type aggregation (long-since deprecated; simpliy means no aggregation) if present, and wrap
  `:field-ids` where appropriate."
  [ag-subclause]
  (mbql.u/replace ag-subclause
    seq? (recur (vec &match))

    [:rows & _]
    nil

    ;; For named aggregations (`[:named <ag> <name>]`) we want to leave as-is and just canonicalize the ag it names
    [:named ag ag-name]
    [:named (canonicalize-aggregation-subclause ag) ag-name]

    [(ag-type :guard #{:+ :- :* :/}) & args]
    (apply
     vector
     ag-type
     ;; if args are also ag subclauses normalize those, but things like numbers are allowed too so leave them as-is
     (for [arg args]
       (cond-> arg
         (mbql-clause? arg) canonicalize-aggregation-subclause)))

    ;; for metric macros (e.g. [:metric <metric-id>]) do not wrap the metric in a :field-id clause
    [:metric _]
    &match

    [(ag-type :guard #{:share :count-where}) pred]
    [ag-type (canonicalize-filter pred)]

    [:sum-where field pred]
    [:sum-where (wrap-implicit-field-id field) (canonicalize-filter pred)]

    ;; something with an arg like [:sum [:field-id 41]]
    [ag-type field]
    [ag-type (wrap-implicit-field-id field)]))

(defn- wrap-single-aggregations
  "Convert old MBQL 95 single-aggregations like `{:aggregation :count}` or `{:aggregation [:count]}` to MBQL 98+
  multiple-aggregation syntax (e.g. `{:aggregation [[:count]]}`)."
  [aggregations]
  (mbql.u/replace aggregations
    seq? (recur (vec &match))

    ;; something like {:aggregations :count} -- MBQL 95 single aggregation
    keyword?
    [[&match]]

    ;; special-case: MBQL 98 multiple aggregations using unwrapped :count or :rows
    ;; e.g. {:aggregations [:count [:sum 10]]} or {:aggregations [:count :count]}
    [(_ :guard (every-pred keyword? (complement #{:named :+ :- :* :/})))
     (_ :guard aggregation-subclause?)
     & _]
    (vec (reduce concat (map wrap-single-aggregations aggregations)))

    ;; something like {:aggregations [:sum 10]} -- MBQL 95 single aggregation
    [(_ :guard keyword?) & _]
    [&match]

    _
    &match))

(defn- canonicalize-aggregations
  "Canonicalize subclauses (see above) and make sure `:aggregation` is a sequence of clauses instead of a single
  clause."
  [aggregations]
  (->> (wrap-single-aggregations aggregations)
       (map canonicalize-aggregation-subclause)
       (filterv identity)))

(defn- canonicalize-order-by
  "Make sure order by clauses like `[:asc 10]` get `:field-id` added where appropriate, e.g. `[:asc [:field-id 10]]`"
  [clauses]
  (mbql.u/replace clauses
    seq? (recur (vec &match))

    ;; MBQL 95 reversed [<field> <direction>] clause
    [field :asc]        (recur [:asc field])
    [field :desc]       (recur [:desc field])
    [field :ascending]  (recur [:asc field])
    [field :descending] (recur [:desc field])

    ;; MBQL 95 names but MBQL 98+ reversed syntax
    [:ascending field]  (recur [:asc field])
    [:descending field] (recur [:desc field])

    [:asc field]  [:asc  (wrap-implicit-field-id field)]
    [:desc field] [:desc (wrap-implicit-field-id field)]

    ;; this case should be the first one hit when we come in with a vector of clauses e.g. [[:asc 1] [:desc 2]]
    [& clauses] (vec (distinct (map canonicalize-order-by clauses)))))

(declare canonicalize-inner-mbql-query)

(defn- canonicalize-source-query [{native? :native, :as source-query}]
  (cond-> source-query
    (not native?) canonicalize-inner-mbql-query))

(defn- non-empty? [x]
  (if (coll? x)
    (seq x)
    (some? x)))

(defn- canonicalize-top-level-mbql-clauses
  "Perform specific steps to canonicalize the various top-level clauses in an MBQL query."
  [mbql-query]
  (cond-> mbql-query
    (non-empty? (:aggregation  mbql-query)) (update :aggregation  canonicalize-aggregations)
    (non-empty? (:breakout     mbql-query)) (update :breakout     (partial mapv wrap-implicit-field-id))
    (non-empty? (:fields       mbql-query)) (update :fields       (partial mapv wrap-implicit-field-id))
    (non-empty? (:filter       mbql-query)) (update :filter       canonicalize-filter)
    (non-empty? (:order-by     mbql-query)) (update :order-by     canonicalize-order-by)
    (non-empty? (:source-query mbql-query)) (update :source-query canonicalize-source-query)))


(def ^:private mbql-clause->canonicalization-fn
  {:fk->
   (fn [_ field-1 field-2]
     [:fk-> (wrap-implicit-field-id field-1) (wrap-implicit-field-id field-2)])

   :datetime-field
   (fn
     ([_ field unit]
      [:datetime-field (wrap-implicit-field-id field) unit])
     ([_ field _ unit]
      [:datetime-field (wrap-implicit-field-id field) unit]))

   :field-id
   (fn [_ id]
     ;; if someone is dumb and does something like [:field-id [:field-literal ...]] be nice and fix it for them.
     (if (mbql-clause? id)
       id
       [:field-id id]))

   :binning-strategy
   (fn canonicalize-binning-strategy
     ([_ field strategy-name]
      [:binning-strategy (wrap-implicit-field-id field) strategy-name])
     ([_ field strategy-name strategy-param]
      (conj (canonicalize-binning-strategy nil field strategy-name) strategy-param)))})

(defn- canonicalize-mbql-clauses
  "Walk an `mbql-query` an canonicalize non-top-level clauses like `:fk->`."
  [mbql-query]
  (walk/prewalk
   (fn [clause]
     (if-not (mbql-clause? clause)
       clause
       (let [[clause-name & _] clause
             f                 (mbql-clause->canonicalization-fn clause-name)]
         (if f
           (try
             (apply f clause)
             (catch Throwable e
               (log/error (tru "Invalid clause:") clause)
               (throw e)))
           clause))))
   mbql-query))

(def ^:private ^{:arglists '([query])} canonicalize-inner-mbql-query
  (comp canonicalize-mbql-clauses canonicalize-top-level-mbql-clauses))

(defn- move-source-metadata-to-mbql-query
  "In Metabase 0.33.0 `:source-metadata` about resolved queries is added to the 'inner' MBQL query rather than to the
  top-level; if we encounter the old style, move it to the appropriate location."
  [{:keys [source-metadata], :as query}]
  (-> query
      (dissoc :source-metadata)
      (assoc-in [:query :source-metadata] source-metadata)))

(defn- canonicalize
  "Canonicalize a query [MBQL query], rewriting the query as if you perfectly followed the recommended style guides for
  writing MBQL. Does things like removes unneeded and empty clauses, converts older MBQL '95 syntax to MBQL '98, etc."
  [{:keys [query parameters source-metadata], :as outer-query}]
  (cond-> outer-query
    source-metadata move-source-metadata-to-mbql-query
    query           (update :query canonicalize-inner-mbql-query)
    parameters      (update :parameters (partial mapv canonicalize-mbql-clauses))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          WHOLE-QUERY TRANSFORMATIONS                                           |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- remove-breakout-fields-from-fields
  "Remove any Fields specified in both `:breakout` and `:fields` from `:fields`; it is implied that any breakout Field
  will be returned, specifying it in both would imply it is to be returned twice, which tends to cause confusion for
  the QP and drivers. (This is done to work around historic bugs with the way queries were generated on the frontend;
  I'm not sure this behavior makes sense, but removing it would break existing queries.)

  We will remove either exact matches:

    {:breakout [[:field-id 10]], :fields [[:field-id 10]]} ; -> {:breakout [[:field-id 10]]}

  or unbucketed matches:

    {:breakout [[:datetime-field [:field-id 10] :month]], :fields [[:field-id 10]]}
    ;; -> {:breakout [[:field-id 10]]}"
  [{{:keys [breakout fields]} :query, :as query}]
  (if-not (and (seq breakout) (seq fields))
    query
    ;; get a set of all Field clauses (of any type) in the breakout. For `datetime-field` clauses, we'll include both
    ;; the bucketed `[:datetime-field <field> ...]` clause and the `<field>` clause it wraps
    (let [breakout-fields (set (reduce concat (mbql.u/match breakout
                                                [:datetime-field field-clause _] [&match field-clause]
                                                mbql.pred/Field?                 [&match])))]
      ;; now remove all the Fields in `:fields` that match the ones in the set
      (update-in query [:query :fields] (comp vec (partial remove breakout-fields))))))

(defn- perform-whole-query-transformations
  "Perform transformations that operate on the query as a whole, making sure the structure as a whole is logical and
  consistent."
  [query]
  (-> query
      remove-breakout-fields-from-fields))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             REMOVING EMPTY CLAUSES                                             |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- non-empty-value?
  "Is this 'value' in a query map considered non-empty (e.g., should we refrain from removing that key entirely?) e.g.:

    {:aggregation nil} ; -> remove this, value is nil
    {:filter []}       ; -> remove this, also empty
    {:limit 100}       ; -> keep this"
  [x]
  (cond
    ;; record types could be something like `driver` and should always be considered non-empty
    (record? x)
    true

    (map? x)
    (seq x)

    ;; a sequence is considered non-empty if it has some non-nil values
    (sequential? x)
    (and (seq x)
         (some some? x))

    ;; any other value is considered non-empty if it is not nil
    :else
    (some? x)))

(defn- remove-empty-clauses
  "Remove any empty or `nil` clauses in a query."
  [query]
  (walk/postwalk
   (fn [x]
     (cond
       (record? x)
       x

       (map? x)
       (m/filter-vals non-empty-value? x)

       :else
       x))
   query))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            PUTTING IT ALL TOGETHER                                             |
;;; +----------------------------------------------------------------------------------------------------------------+

;; TODO - should have this validate against the MBQL schema afterwards, right? Maybe once we get closer to making this
;; all mergable
(def ^{:arglists '([outer-query])} normalize
  "Normalize the tokens in a Metabase query (i.e., make them all `lisp-case` keywords), rewrite deprecated clauses as
  up-to-date MBQL 2000, and remove empty clauses."
  (comp remove-empty-clauses
        perform-whole-query-transformations
        canonicalize
        normalize-tokens))

(defn normalize-fragment
  "Normalize just a specific fragment of a query, such as just the inner MBQL part or just a filter clause. `path` is
  where this fragment would normally live in a full query.

    (normalize-fragment [:query :filter] [\"=\" 100 200])
    ;;-> [:= [:field-id 100] 200]"
  {:style/indent 1}
  [path x]
  (if-not (seq path)
    (normalize x)
    (get (normalize-fragment (butlast path) {(last path) x}) (last path))))
