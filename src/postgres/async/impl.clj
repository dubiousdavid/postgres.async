(ns postgres.async.impl
  (:require [clojure.string :as string]
            [clojure.core.async :refer [chan put! go <!]])
  (:import [java.util.function Consumer]
           [com.github.pgasync ResultSet]
           [com.github.pgasync.impl PgRow]))

(set! *warn-on-reflection* true)

(defmacro defasync [name args]
  `(defn ~name [~@args]
     (let [c# (chan 1)]
       (~(symbol (subs (str name) 1)) ~@args #(put! c# [%1 %2]))
       c#)))

(defmacro consumer-fn [[param] body]
  `(reify Consumer (accept [_# ~param]
                     (~@body))))

(defn result->map [^ResultSet result]
  (let [columns (.getColumns result)
        row->map (fn [^PgRow row rowmap ^String col]
            (assoc rowmap (keyword (.toLowerCase col)) (.get row col)))]
    {:updated (.updatedRows result)
     :rows (vec (map (fn [row]
                           (reduce (partial row->map row) {} columns))
                         result))}))

(defn- list-columns [data]
  (for [e data] (-> e (first) (name))))

(defn- list-params [start end]
  (for [i (range start end)] (str "$" i)))

(defn create-insert-sql [{:keys [table returning]} data]
  (str "INSERT INTO " table " ("
       (string/join ", " (list-columns data))
       ") VALUES ("
       (string/join ", " (list-params 1 (inc (count data)))) ")"
       (when returning
         (str " RETURNING " returning))))

(defn create-update-sql [{:keys [table returning where]} data]
  (str "UPDATE " table
       " SET ("
       (string/join "," (list-columns data))
       ")=("
       (string/join "," (list-params (count where) (+ (count where) (count data))))
       ") WHERE " (first where)
       (when returning
         (str " RETURNING " returning))))

(defn async-sql-bindings
  "Converts bindings x (f) to [x err] (if [err] [nil err] (<! (f)))"
  [bindings err]
  (let [vars (map (fn [v]
                    [v err])
                  (take-nth 2 bindings))
        fs   (map (fn [f]
                    `(if ~err [nil ~err] (<! ~f)))
                  (take-nth 2 (rest bindings)))]
    (list* [err err] [nil nil] (interleave vars fs))))