(ns clj-duration.core-test
  (:require [clojure.test :refer :all]
            [clj-duration.core :refer :all]
            [clojure.set :as set]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str])
  (:import java.time.Duration))

(defn try-duration [dur-str]
  (try (read-string dur-str)
       (catch Exception ex ex)))

(deftest a-test
  (testing "millis"
    (is (= (try-duration "#unit/duration \"123ms\"") (Duration/ofMillis 123)))))

(defn as-duration-string [duration-parts]
  (->> duration-parts
       (map #(format "%d%s"
                     (:v %)
                     (name (:unit %))))
       (str/join " ")
       (format "#unit/duration \"%s\"")))

(defrecord DurationUnit [unit wrap-val])

(def test-units [(->DurationUnit :Y 292)
                 (->DurationUnit :D 365)
                 (->DurationUnit :h 24)
                 (->DurationUnit :m 60)
                 (->DurationUnit :s 60)
                 (->DurationUnit :ms 1000)
                 (->DurationUnit :µs 1000)
                 (->DurationUnit :ns 1000)])

(defn sets-with-e [e t]
  (into #{} (map #(conj % e) t)))

(defn powerset [s]
  (if (empty? s)
    #{#{}}
    (let [e (first s)
          t (set/difference s #{e})]
      (set/union (powerset t)
                 (sets-with-e e (powerset t))))))

(defn order-units [included-units]
  (filter included-units test-units))

(defn unit-generator [{wrap-val :wrap-val :as duration-unit}]
  (gen/fmap (fn [v] (assoc duration-unit :v (long v)))
            ;; units that have a value of 0 ain't part of the printed representation
            ;; so start at 1.
            (gen/choose 1 (dec wrap-val))))

(defn subset [ss]
  (gen/elements (vec (powerset ss))))

(def duration-generator (gen/bind (subset (set test-units))
                                  (fn [units]
                                    (apply gen/tuple
                                           (map unit-generator
                                                (order-units units))))))

(defspec property-inside-unit-round-trips
         1000
         (prop/for-all [duration-string (gen/fmap as-duration-string duration-generator)]
           (= duration-string (pr-str (read-string duration-string)))))

(defn lower-units [largest-unit]
  (->> (drop-while #(not= largest-unit (:unit %))
                   test-units)
       (drop 1)))

(def conversions (->> test-units
                      (reverse)
                      (partition 2 1)
                      (reduce
                        (fn [acc [{to-unit :unit
                                   to-wrap-val :wrap-val}
                                  {from-unit :unit}]]
                          (assoc acc
                            from-unit
                            (assoc (into {}
                                         (map (fn [[k v]]
                                                [k (* v to-wrap-val)])
                                              (get acc to-unit)))
                              to-unit
                              (long to-wrap-val))))
                          {})))

(defn conversion-factor [unit replacement]
  (get-in conversions [unit replacement]))

(let [unit->idx (into {}
                      (map-indexed #(vector %2 %)
                                   (map :unit test-units)))]
  (defn sort-duration-parts [duration-parts]
   (sort-by (fn [{u :unit}]
              (get unit->idx u))
            duration-parts)))

(defn lower-unit-generator [unit-to-replace]
  (->> unit-to-replace
       (lower-units)
       (map :unit)
       (gen/elements)))

(defn unit->replacement-generator [units-to-replace]
  (->> units-to-replace
       (map (fn [unit-to-replace]
              (gen/tuple (gen/return unit-to-replace)
                         (lower-unit-generator unit-to-replace))))
       (apply gen/tuple)))

(defn replace-duration-parts [duration-units unit->replacement]
  (map (fn [{:keys [v unit] :as duration-record}]
         (if-let [replacement (get unit->replacement unit)]
           (if (not= replacement unit)
             {:unit replacement
              :v (* (conversion-factor unit replacement) v)}
             duration-record)
           duration-record))
       duration-units))

(defn sum-same-units [overflown-duration]
  (->> overflown-duration
       (group-by :unit)
       (vals)
       (map (fn [same-unit-durations]
              (reduce
                (fn [acc {v :v}]
                  (update-in acc [:v] + v))
                same-unit-durations)))))

(defn create-overflown-duration [duration-units]
  (let [dropable-units (into #{}
                             (map :unit
                                  (remove #(= :ns (:unit %))
                                      duration-units)))]
    (-> (gen/not-empty (subset dropable-units)
                       ;; increase retries to increase likelihood that a non-empty set
                       ;; is returned in the case of single-unit durations
                       100)
        (gen/bind unit->replacement-generator)
        (->> (gen/fmap (partial into {}))
             (gen/fmap (partial replace-duration-parts duration-units))
             (gen/fmap sum-same-units)
             (gen/fmap sort-duration-parts)))))

(def overflown-duration-generator (gen/bind (gen/not-empty (gen/such-that (fn [units]
                                                              (not= [:ns] (mapv :unit units)))
                                                            duration-generator))
                                            (fn [duration-units]
                                              (gen/tuple (gen/return duration-units)
                                                         (create-overflown-duration duration-units)))))

(defspec property-overwrapping-units
         1000
         (prop/for-all [[duration-string overflown-duration-string] (gen/fmap (partial map as-duration-string)
                                                                              overflown-duration-generator)]
           (= (read-string duration-string) (read-string overflown-duration-string))))

