# `clj-duration` [![Build Status](https://travis-ci.org/gerrit-hntschl/clj-duration.svg?branch=master)](https://travis-ci.org/gerrit-hntschl/clj-duration)

Human-friendly reader literal for durations.

Provides the reader literal `#unit/duration` which models time-based amounts
of time using java.time.Duration.

Durations are printed using the most appropriate duration-based units:
```clojure
(duration-of-millis 1234567)
; => #unit/duration "20m 34s 567ms"
```

Supported units are:

* `Y`: years, exactly 365 days, ignoring leap years.
* `D`: days, exactly 24 hours, ignoring daylight savings effects.
* `m`: minutes, exactly 60 seconds, ignoring leap seconds.
* `s`: seconds, exactly 1000 milliseconds.
* `ms`: milliseconds, exactly 1000 microseconds.
* `µs`: microseconds, exactly 1000 nanoseconds.
* `ns`: nanoseconds.

`#unit/duration` prints only non-zero duration-units to increase readability.

### Note: this library requires JDK8.

## Motivation

When building complex systems we have to measure, benchmark, monitor and/or schedule
how long tasks run, in what frequency they should run or abort them when
they run for too long. The concept of `duration` occurs time and time again,
however in Clojure we don't have a first class representation for it.

### So why should we bother?

The scale of a duration can be fairly large, ranging from nanoseconds to years.
That is why units of differing granularity like microseconds, minutes, days etc. have been invented
by humanity. When somebody tells you that a movie lasts 1 hour and 32 minutes, then it conveys digestible information,
while the knowledge that the movie has a duration of 5520000 milliseconds probably doesn't help you at all. In programming,
one might argue that durations should be represented in a way that the computer can deal with it. However,
programs are written by people and especially in an interactive REPL-workflow it is beneficial to have a concept
of duration that can be understood by a person in a single glance. Representing a duration as something like `1h 32m 5s`
provides great readability and allows us to use our natural feeling for time in contrast to be puzzeld by a
huge opaque amount of milliseconds.

### And now what?

There is another dimension which is typically represented in computer systems by an inhuman train of digits:
dates also known as milliseconds since epoch. The millisecond representation works nicely for automated consumers,
but manually comparing two millisecond dates on the REPL would be tedious and inefficient. Enter tagged literals:
`#inst "2014-07-16T15:09:08.769-00:00"` provides a nice solution for the date situation. A person can easily parse
the information and likewise it can be serialized and passed around without problems. However,
there is no built-in reader literal for durations.

To deal more efficiently with durations in Java, one can use the `java.util.concurrent.TimeUnit` relict. APIs using
this class require the user to split one semantic piece of information, e.g. 150 seconds, into two separate arguments,
e.g. 150 and `TimeUnit/SECONDS`. A little bit awkward, but maybe good enough. In Clojure we can certainly do better. Java's problem
here is that the language designers didn't follow Guy Steele's advice in his seminal
presentation [Growing a Language](https://www.youtube.com/watch?v=_ahvzDzKdB0). The users of the language
cannot organically grow Java by adding operators or primitives that look like being part of the language. Clojure,
which fortunately is a Lisp, allows us to extend the language via tagged literals.

### Finally some beef!

Although we cannot be as concise as the `#inst` literal, the tagged literal `#unit/duration` allows it to represent
durations in a readable, concise fashion.
```clojure
(require '[clj-duration.core :as d])
(d/duration-of-millis 5520000)
; => #unit/duration "1h 32m"
#unit/duration "123456s"
; => #unit/duration "1D 10h 17m 36s"
```
Durations can thus easily be encoded into EDN files and send over the wire. A single glance allows to find the
larger of two durations.

## `clj-duration.core`

The `clj-duration.core` namespace provides some example applications of durations,
but `#unit/duration` could be used in multiple other places, e.g. everywhere a timeout is specified...

### `duration`
Like `clojure.core/time` but prints readable durations.

```clojure
(d/duration (some-heavy-computation ...))
; Elapsed time: 1m 7s 123ms 566µs
; => <return-val>
```

### `measure-to-agent` and `measured`
Sometimes simply printing the duration is not enough, but the duration should be sent somewhere else.
`measure-to-agent` wraps a function and sends the duration it took to execute the function to an agent,
that gathers those measurements in a vector.

```clojure
(def measurements-agent (agent []))
(defn f [n] (Thread/sleep n))
(def measured-f (d/measure-to-agent measurements-agent f))
(measured-f 100)
(measured-f 1000)
@measurements-agent
; => [#unit/duration "101ms 241µs" #unit/duration "1s 696µs"]
```

A scheduled task could aggregate those measurements and send to your monitoring system of choice.

`measured` allows you to define your own `measurement-handler-fn` that could additionally record the arguments with
which the wrapped function was invoked.

### `schedule`

Provides a data interface for Java's `ScheduledExecutorService`.

```clojure
(def scheduler (java.util.concurrent.Executors/newScheduledThreadPool 3))
(d/schedule scheduler
            {:schedule :at-fixed-rate
             :delay #unit/duration "1m 30s"
             :initial-delay #unit/duration "500ms"
             :task-fn (fn [] (println "workwork"))})
;; after 500 milliseconds
; workwork
```

### Wait, there is a standard for durations...

[ISO 8601](http://en.wikipedia.org/wiki/ISO_8601#Durations) indeed defines a format for durations which looks like
this: `P3Y6M4DT12H30M5S`. As the purpose of this library is to maximize
readability I decided against using this format as it is too dense, stops at *seconds* and requires context
(the first *M* means *month*, while the second *M* stands for *minutes* because it is preceded by the *T*).
This might be personal taste, but I prefer `3Y 184D 12h 30m 5s` over `P3Y6M4DT12H30M5S`.

## Testing

```
lein test
```

As the implementation of the tagged literal is basically a parser and the corresponding printer, it is an excellent
fit for using [clojure.test.check](https://github.com/clojure/test.check) to verify that the parsing and printing of `#unit/duration`s
round-trips. This is quite easy for durations with amounts inside a duration-based unit, i.e. none of the amounts wraps
into the next larger unit.

The more interesting test case is the one that verifies that durations are correctly *shortened* to the most appropriate
units. By putting a bit more effort into the generator for the test it is possible to calculate the necessary conversion
beforehand and thus producing a `#unit/duration` string that has to be *shortened* into larger units from a valid
*not-shorten-able* sequence of units. The resulting [test](https://github.com/gerrit-hntschl/clj-duration/blob/master/test/clj_duration/core_test.clj#L165)
just parses the two equivalent durations into a `java.time.Duration` and compares them for equality.


## License
clj-duration is Copyright © 2014 Gerrit Hentschel

Distributed under the Eclipse Public License.