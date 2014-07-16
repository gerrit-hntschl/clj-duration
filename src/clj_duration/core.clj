(ns ^{:requires "JDK8"}
    clj-duration.core
  "Provides the reader literal #unit/duration which models time-based amounts
  of time using java.time.Duration.
  Durations are printed using the most appropriate duration-based units, e.g.
  (duration-of-millis 1234567) => #unit/duration \"20m 34s 567ms\".
  Supported units are:
  - Y: years, exactly 365 days, ignoring leap years.
  - D: days, exactly 24 hours, ignoring daylight savings effects.
  - m: minutes, exactly 60 seconds, ignoring leap seconds.
  - s: seconds, exactly 1000 milliseconds.
  - ms: milliseconds, exactly 1000 microseconds.
  - µs: microseconds, exactly 1000 nanoseconds.
  - ns: nanoseconds.
  #unit/duration contains only non-zero duration-units to increase readability."
  (:require [clojure.string :as str])
  (:import java.time.Duration
           [java.time.format DateTimeParseException]
           [java.time.temporal ChronoUnit]
           [java.util.concurrent TimeUnit ScheduledExecutorService ScheduledFuture]))

(set! *warn-on-reflection* true)

;;;;;;;;; parser implementation ;;;;;;;;

(def ^:const duration-regex
  #"^(?:(\d+)Y)?(?: ?(\d+)D)?(?: ?(\d+)h)?(?: ?(\d+)m)?(?: ?(\d+)s)?(?: ?(\d+)ms)?(?: ?(\d+)µs)?(?: ?(\d+)ns)?$")

(defmacro plus-duration-fn [duration-fn-name]
  (let [{:keys [factor]} (meta duration-fn-name)
        duration-amount-sym (gensym)]
    `(fn [~duration-amount-sym]
       (if-not ~duration-amount-sym
         identity
         (fn [^Duration duration#]
           (.. duration#
               (~duration-fn-name ~(if factor
                                     `(* ~factor ~duration-amount-sym)
                                     duration-amount-sym))))))))

(def plus-duration-fns                                      ; can't map macros...
  [(plus-duration-fn ^{:factor 365} plusDays)
   (plus-duration-fn plusDays)
   (plus-duration-fn plusHours)
   (plus-duration-fn plusMinutes)
   (plus-duration-fn plusSeconds)
   (plus-duration-fn plusMillis)
   (plus-duration-fn ^{:factor 1000} plusNanos)
   (plus-duration-fn plusNanos)])

(defn- parse-int [^String s]
  (when s
    (Long/parseLong s)))

(defn parse-duration [duration-char-seq]
  (let [[match? & amounts] (re-find duration-regex duration-char-seq)]
    (if match?
      (->> amounts
           (map parse-int)
           (map (fn [f a]
                  (f a))
                plus-duration-fns)
           (reduce (fn [duration f]
                     (f duration))
                   Duration/ZERO))
      (throw (RuntimeException. (str "unrecognized duration syntax: " duration-char-seq))))))

;;;;;;;; printer implementation ;;;;;;;;;;;

;; MU char is \u00B5
(def ^:const units ["Y" "D" "h" "m" "s" "ms" "µs" "ns"])

(defn ^String readable-duration [^Duration duration]
  (let [sb (StringBuilder.)
        total-seconds (.getSeconds duration)
        total-nanos (.getNano duration)
        nanos (mod total-nanos 1000)
        total-micros (long (/ total-nanos 1000))
        micros (mod total-micros 1000)
        millis (long (/ total-micros 1000))
        seconds (mod total-seconds 60)
        total-minutes (long (/ total-seconds 60))
        minutes (mod total-minutes 60)
        total-hours (long (/ total-minutes 60))
        hours (mod total-hours 24)
        total-days (long (/ total-hours 24))
        days (mod total-days 365)
        total-years (long (/ total-days 365))]
    (loop [first? true
           [n & more-amounts] [total-years days hours minutes seconds millis micros nanos]
           [unit & more-units] units]
      (when n
        (if (> n 0)
          (do (when-not first?
                (.append sb " "))
              (.append sb n)
              (.append sb unit)
              (recur false more-amounts more-units))
          (recur first? more-amounts more-units))))
    (.toString sb)))

(defn print-duration
  [^java.time.Duration duration, ^java.io.Writer w]
  (.write w "#unit/duration \"")
  (.write w (readable-duration duration))
  (.write w "\""))

(defmethod print-method java.time.Duration
  [duration w]
  (print-duration duration w))

(defmethod print-dup java.time.Duration
  [duration w]
  (print-duration duration w))

;;;;;;; public api ;;;;;;;;;

(defn duration-of-millis [millis]
  (Duration/ofMillis millis))

(defn duration-of-nanos [nanos]
  (Duration/ofNanos nanos))

(defmacro duration
  "Like 'clojure.core/time' but prints elapsed time as human-readable duration"
  [expr]
  `(let [start# (System/nanoTime)
         result# ~expr]
     (println "Elapsed time:" (readable-duration (duration-of-nanos (- (System/nanoTime) start#))))
     result#))

(defn measured
  "Wraps f such that its execution time is measured (as Duration) and
  passed to measurement-fn along with the args f was invoked with, in that order."
  [measurement-fn f]
  (fn [& args]
    (let [start (System/nanoTime)
          result (apply f args)
          end (System/nanoTime)
          duration (duration-of-nanos (- end start))]
      (apply measurement-fn duration args)
      result)))

(defn measure-to-agent
  "Takes an agent and a function f and returns a function that wraps f
  by conjing the execution times of f to the state of the agent."
  [measurements-agent f]
  (measured
    (fn [measurement & _]
      (send measurements-agent conj measurement))
    f))

(let [unit TimeUnit/MILLISECONDS]
  (defn schedule
    "Schedules a task-fn for execution in the given ScheduledExecutorService with the given :delay.
    The :schedule defines whether the task should be run :once, :at-fixed-rate or :with-fixed-delay. "
    [^ScheduledExecutorService scheduled-executor
     {:keys [schedule task-fn ^Duration delay ^Duration initial-delay]
      :or {initial-delay Duration/ZERO}
      :as params}]
    {:pre [((every-pred :task-fn :delay :schedule) params)
           (#{:once :at-fixed-rate :with-fixed-delay} schedule)]}
    (let [initial-delay (.toMillis initial-delay)
          delay (.toMillis delay)
          scheduled-future (case schedule
                             :once (.schedule scheduled-executor ^Runnable task-fn delay unit)
                             :with-fixed-delay (.scheduleWithFixedDelay scheduled-executor task-fn initial-delay delay unit)
                             :at-fixed-rate (.scheduleAtFixedRate scheduled-executor task-fn initial-delay delay unit))]
      (fn [] (.cancel ^ScheduledFuture scheduled-future true)))))

(set! *warn-on-reflection* false)
