(ns de.neominik.uvl.parser
  (:require [instaparse.core :as insta]
            [clojure.string :as s]
            [de.neominik.uvl.transform :refer :all]))

(def ^:private indent "_{_")
(def ^:private dedent "_}_")

(defn- level [line]
  (if (s/blank? line)
    0
    (- (.length line) (.length (s/triml line)))))

(defn- dec-0 [n] (if (zero? n) 0 (dec n)))
(defn logical-lines []
  (fn [xf]
    (let [linecomment (atom false)
          curlybraces (atom 0)
          brackets (atom 0)
          string (atom false)
          current-line (atom "")
          explicit?! #(when (s/ends-with? @current-line "\\")
                        (swap! current-line subs 0 (dec (count @current-line))))
          implicit?! #(when (not (or (= 0 @curlybraces @brackets) @string))
                        (swap! current-line str \ ))]
      (fn
        ([] (xf))
        ([result] (if (s/blank? @current-line) (xf result) (xf (xf result @current-line))))
        ([result char]
         (when-not @linecomment
           (when-not (= \newline char) (swap! current-line str char))
           (when (= \" char)
             (if @string
               (when-not (s/ends-with? @current-line "\\\"")
                 (reset! string false))
               (reset! string true)))
           (when-not @string
             (case char
               \[ (swap! brackets inc)
               \] (swap! brackets dec-0)
               \{ (swap! curlybraces inc)
               \} (swap! curlybraces dec-0)
               \/ (when (s/ends-with? @current-line "//")
                    (swap! current-line subs 0 (- (count @current-line) 2))
                    (reset! linecomment true))
               nil)))
         (when (= char \newline) (reset! linecomment false))

         (if (or (and (= char \newline) (or (explicit?!) (implicit?!))) (not= char \newline))
           result
           (let [l @current-line]
             (reset! current-line "")
             (xf result l))))))))

(defn emit-tokens []
  (fn [xf]
    (let [stack (atom '(0))
          line-num (atom 0)]
      (fn
        ([] (xf))
        ([result]
         (reduce xf result (repeat (dec (count @stack)) dedent)))
        ([result line]
         (let [prev (peek @stack)
               curr (level line)]
           (swap! line-num inc)
           (cond
             (s/blank? line) result
             (= curr prev) (xf result line)
             (> curr prev) (do
                             (swap! stack conj curr)
                             (xf (xf result indent) line))
             :else (do
                     (let [[dropped kept] (split-with (partial < curr) @stack)]
                       (reset! stack (list* kept))
                       (when-not (= (peek @stack) curr)
                         (throw (AssertionError.
                                 (format "Wrong indentation. Expected one of %s but found %d in line %d"
                                         (conj (vec dropped) (peek @stack))
                                         curr
                                         @line-num))))
                       (xf (reduce xf result (repeat (count dropped) dedent)) line))))))))))

(defn pre-lex [s]
  (let [transducer (comp (logical-lines) (emit-tokens))
        with-tokens (transduce transducer conj s)]
    (s/join "\n" with-tokens)))

(def ws (insta/parser "ws = #'[\\s]+'"))
(def file "uvl.bnf")

(defn p [s]
  (let [parser (insta/parser (slurp file) :auto-whitespace ws)
        parses (insta/parses parser (pre-lex s))
        ps (insta/transform transform-map parses)]
    (if (insta/failure? ps)
      (insta/get-failure ps)
      (if (> (count ps) 1)
        (do (println "Ambigous grammar!") ps)
        (first ps)))))