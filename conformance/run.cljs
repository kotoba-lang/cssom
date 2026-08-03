(ns conformance.run
  "Differential layout conformance: cssom.layout vs a real Blink browser.

   For every case in cases.edn this renders the SAME markup twice --
   once through the real pipeline this repo is part of (htmldom parse ->
   cssom.core cascade -> cssom.layout draw-ops) and once in a real
   headless Brave/Chrome -- and compares the LINE STRUCTURE of the two:
   the ordered list of lines, each line being the whitespace-normalized
   text that landed on it, in left-to-right order.

   Why line structure and not pixels: this engine has no glyph shaping
   (see cssom.layout's ns docstring -- widths come from a
   `(long (* 0.6 font-size))` per-character approximation unless a host
   supplies :measure-text), so its absolute coordinates CANNOT match a
   real font's and comparing them would measure the approximation, not
   the layout. What text shares a line, in what order, and how many lines
   there are is the part both engines genuinely agree on when the layout
   is right -- and it is exactly the part this engine got wrong for
   everything inline until 2026-08-03.

   The browser side is read with `--headless --dump-dom`: an inline script
   measures every text node with Range.getClientRects(), base64s the
   result into a <pre>, and dump-dom hands us back the DOM containing it.
   No CDP client, no Playwright, no extra dependency -- the same reason
   this repo's own smoke checks avoid a driver.

   Usage:
     nbb --classpath \"src:<dom-gpu>/src:<htmldom>/src\" conformance/run.cljs \\
       [--browser <path to Brave/Chrome binary>] [--width 800] \\
       [--ledger <path to append a result entry to>]"
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cssom.core :as css]
            [cssom.layout :as layout]
            [htmldom.core :as html]
            [kotoba.wasm.dom :as dom]))

(def browser-candidates
  ["/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"
   "/Applications/Brave Browser Beta.app/Contents/MacOS/Brave Browser Beta"
   "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
   "/Applications/Chromium.app/Contents/MacOS/Chromium"])

(defn- parse-args [argv]
  (loop [args (vec argv) out {:width 800}]
    (if-let [a (first args)]
      (case a
        "--browser" (recur (drop 2 args) (assoc out :browser (second args)))
        "--width" (recur (drop 2 args) (assoc out :width (js/parseInt (second args) 10)))
        "--ledger" (recur (drop 2 args) (assoc out :ledger (second args)))
        "--only" (recur (drop 2 args) (assoc out :only (second args)))
        (recur (rest args) out))
      out)))

(defn- find-browsers
  "The ordered list of oracle candidates to try. Brave is first — it is the
   named comparison target — but every candidate here is the SAME layout
   engine (Blink): Brave is Chromium plus network/privacy shields, and
   shields do not change layout. So when Brave's headless mode refuses to
   produce a dump in this environment (measured: it writes zero bytes and
   never exits, while Chrome writes its dump and then hangs, which the
   SIGKILL timeout handles), falling through to Chrome/Chromium measures
   the identical engine rather than a different one. The oracle that
   actually produced the numbers is printed and recorded in the ledger, so
   a fallback is never silent."
  [explicit]
  (if explicit
    (if (fs/existsSync explicit)
      [explicit]
      (throw (ex-info "browser not found at --browser path" {:path explicit})))
    (let [found (filterv fs/existsSync browser-candidates)]
      (when (empty? found)
        (throw (ex-info "no Blink browser found; pass --browser <path>"
                        {:looked-at browser-candidates})))
      found)))

;; ---- the browser (oracle) side ----

(def measure-script
  "Runs INSIDE the real browser, once, over EVERY case container on the
   page. Each text node is measured with a Range so that a wrapped node
   reports one rect per line, which is how the harness detects wrapping
   (and then declines to score that case on text equality). Output is
   base64 so arbitrary case text can never break the <pre> we read it back
   out of.

   All cases share one page — and therefore one browser launch — because a
   launch costs seconds and the corpus is meant to grow into the hundreds."
  "
  (function () {
    var out = {};
    var roots = document.querySelectorAll('.kotoba-case');
    for (var i = 0; i < roots.length; i++) {
      var root = roots[i];
      var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
      var words = [];
      var node;
      while ((node = walker.nextNode())) {
        var text = node.nodeValue;
        if (!text.trim()) continue;
        var re = /\\S+/g, m;
        while ((m = re.exec(text))) {
          var range = document.createRange();
          range.setStart(node, m.index);
          range.setEnd(node, m.index + m[0].length);
          var r = range.getBoundingClientRect();
          if (!r.width && !r.height) continue;
          words.push({ text: m[0], top: r.top, bottom: r.bottom, left: r.left });
        }
      }
      out[root.id] = words;
    }
    var pre = document.createElement('pre');
    pre.id = 'kotoba-conformance-out';
    pre.textContent = btoa(unescape(encodeURIComponent(JSON.stringify(out))));
    document.body.appendChild(pre);
  })();
  ")

(defn- scope-css
  "Prefixes every selector in a case's CSS with that case's container id, so
   all cases can share one page without one case's `li::before` reaching
   into another's markup. Deliberately naive (split on `}`, then on `,`):
   the corpus is hand-written and stays within plain selector lists, and a
   real @media/@supports block would be visible as a mis-scoped rule rather
   than silently wrong."
  [css scope]
  (when-not (str/blank? (str css))
    (->> (str/split css #"\}")
         (keep (fn [chunk]
                 (when-let [[sel body] (when (str/includes? chunk "{") (str/split chunk #"\{" 2))]
                   (str (->> (str/split sel #",")
                             (map str/trim)
                             (remove str/blank?)
                             (map #(str scope " " %))
                             (str/join ", "))
                        " {" body "}"))))
         (str/join "\n"))))

(defn- corpus-page [cases width]
  (str "<!doctype html><html><head><meta charset=\"utf-8\"><style>"
       "html,body{margin:0;padding:0}"
       ".kotoba-case{width:" width "px;font-family:monospace;font-size:14px;line-height:20px}"
       (->> cases
            (map-indexed (fn [i c] (scope-css (:css c) (str "#case-" i))))
            (remove nil?)
            (str/join "\n"))
       "</style></head><body>"
       (->> cases
            (map-indexed (fn [i c]
                           (str "<div class=\"kotoba-case\" id=\"case-" i "\">" (:html c) "</div>")))
            (str/join "\n"))
       "<script>" measure-script "</script></body></html>"))

(defn- run-browser!
  "Runs the corpus page in a real Blink browser and returns its measurement
   block.

   Two hard-won details, both measured on Brave 151.1.93.129 rather than
   assumed:

   1. `--headless=old`. `--headless=new --dump-dom` prints NOTHING at all
      (exit 0, empty stdout) and bare `--headless` never returns. Old
      headless is deprecated upstream, so when a future Brave drops it this
      is the first thing to re-measure -- the harness then fails loudly (no
      measurement block) instead of silently scoring zero.

   2. Output goes to a FILE, through `sh -c ... > file`, never through a
      pipe. Chromium's child processes inherit stdout and keep it open
      after the parent is killed, so a pipe never reaches EOF and the
      reader hangs forever -- `spawnSync`'s own `:timeout` does not help,
      because it kills the parent and then still waits on the pipe.
      Redirecting to a file removes the EOF dependency entirely, and
      `timeout` bounds the run. (`--virtual-time-budget` is also omitted on
      purpose: with old headless it kept the browser alive indefinitely on
      a page that had already finished rendering.)"
  [browser file]
  (let [profile (fs/mkdtempSync (path/join (os/tmpdir) "kotoba-conf-"))
        out-file (path/join profile "dump.html")
        ;; `timeout -s KILL`, not plain `timeout`: measured here, headless
        ;; Chromium WRITES its --dump-dom output and then never exits, and
        ;; it ignores SIGTERM, so a plain `timeout` hangs forever. SIGKILL
        ;; after the dump is written costs nothing -- the exit status is
        ;; deliberately ignored below and only the file content is trusted.
        cmd (str "timeout -s KILL 30 '" browser "' --headless=old --disable-gpu"
                 " --no-first-run --no-default-browser-check --disable-extensions"
                 " --user-data-dir='" profile "'"
                 " --dump-dom 'file://" file "' > '" out-file "' 2>/dev/null")
        res (cp/spawnSync "/bin/sh" #js ["-c" cmd] #js {:encoding "utf8" :timeout 90000})
        stdout (if (fs/existsSync out-file) (fs/readFileSync out-file "utf8") "")]
    (when-not (str/includes? stdout "kotoba-conformance-out")
      (fs/rmSync profile #js {:recursive true :force true})
      (throw (ex-info "browser produced no measurement block"
                      {:status (.-status res) :bytes (count stdout)})))
    (let [start (str/index-of stdout "kotoba-conformance-out\">")
          from (+ start (count "kotoba-conformance-out\">"))
          end (str/index-of stdout "</pre>" from)
          parsed (-> (js/Buffer.from (subs stdout from end) "base64")
                     (.toString "utf8")
                     js/JSON.parse
                     (js->clj :keywordize-keys true))]
      (fs/rmSync profile #js {:recursive true :force true})
      parsed)))

(defn- normalize [s]
  ;; Case-folded on purpose: `text-transform: uppercase` genuinely rewrites
  ;; what cssom.layout emits (it must -- wrapping has to measure the
  ;; transformed text) while a real browser leaves the DOM text alone and
  ;; upper-cases at paint time. Both are correct, and comparing them
  ;; case-sensitively would score a correct engine as wrong.
  (-> (str s) (str/replace #"\s+" " ") str/trim str/lower-case))

(defn- cluster-lines
  "Groups measured WORDS into line boxes by vertical overlap, then reads
   each line left to right.

   Overlap, not an exact `top` match: a `<b>` and the plain text beside it
   sit on the same line but their boxes differ by a pixel or two because
   the fonts differ, and Blink's own per-run boxes are not top-aligned
   either. Clustering on whether a word's vertical MIDPOINT falls inside
   the line's current vertical span is what both engines agree on when the
   layout is right.

   The same function is applied to BOTH sides -- the browser's word rects
   and cssom.layout's own draw-ops -- so neither side gets a grouping rule
   the other doesn't. Word-level measurement (rather than per text NODE)
   is what makes wrapped text comparable at all: a wrapped node has one
   rect per line, and only its individual words can be attributed to the
   line they actually landed on."
  [words]
  (->> words
       (sort-by (fn [w] [(:top w) (:left w)]))
       (reduce (fn [lines w]
                 (let [mid (/ (+ (:top w) (:bottom w)) 2)
                       line (peek lines)]
                   (if (and line (< (:top line) mid (:bottom line)))
                     (conj (pop lines)
                           (-> line
                               (update :words conj w)
                               (assoc :top (min (:top line) (:top w))
                                      :bottom (max (:bottom line) (:bottom w)))))
                     (conj lines {:top (:top w) :bottom (:bottom w) :words [w]}))))
               [])
       (mapv (fn [line]
               (->> (:words line)
                    (sort-by :left)
                    (map :text)
                    (map normalize)
                    (remove str/blank?)
                    (str/join " "))))
       (filterv (complement str/blank?))))

;; ---- the cssom side ----

(defn- engine-lines
  "cssom.layout's own answer, in the same shape the oracle's is read into.

   Each `:text` draw-op is split back into words positioned by this
   engine's own width model, because that is the granularity the browser
   side is measured at; the op's vertical span is `[y, y + font-size]`,
   which is exactly the em box the real hosts paint into (dom-gpu draws at
   `y + font-size`, the baseline). Splitting per word also means a wrapped
   line compares correctly rather than as one blob."
  [{:keys [html css]} width]
  (let [doc (html/parse-into-document (str "<div id=\"root\">" html "</div>"))
        ;; apply-cascade runs even with no author CSS: it is also what folds
        ;; a `style="..."` attribute's :style-inline into the :style/* attrs
        ;; cssom.layout actually reads, so skipping it would silently drop
        ;; every inline style in the corpus.
        doc (css/apply-cascade doc (css/parse-rules (or css "")))
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width width})
        text-ops (filter #(= :text (:draw/op %)) ops)
        char-w (fn [fs] (long (* 0.6 (or fs 14))))]
    (->> text-ops
         (mapcat (fn [op]
                   (let [fs (:font-size op 14)
                         cw (char-w fs)]
                     (loop [words (str/split (str (:text op)) #"(?=\s)|(?<=\s)")
                            x (:x op)
                            out []]
                       (if-let [w (first words)]
                         (recur (rest words) (+ x (* cw (count w)))
                                (if (str/blank? w)
                                  out
                                  (conj out {:text w :left x
                                             :top (:y op) :bottom (+ (:y op) fs)})))
                         out)))))
         cluster-lines)))

;; ---- comparison ----

(defn- compare-case [oracle-words width c]
  (let [lines (cluster-lines oracle-words)
        mine (try (engine-lines c width)
                  (catch :default e {:error (ex-message e)}))]
    (cond
      (map? mine)
      {:id (:id c) :group (:group c) :status :error :detail (:error mine)}

      ;; Generated content (::before/::after, list markers) is NOT in the
      ;; DOM text the oracle walks -- a real browser paints it from the box
      ;; tree, and no Range can reach it. cssom.layout synthesizes it as
      ;; real text, so the two sides are structurally incomparable here
      ;; through no fault of either. Marked in the corpus, excluded from
      ;; the score, and printed, rather than silently counted as a failure.
      (:oracle/blind c)
      {:id (:id c) :group (:group c) :status :unscorable
       :detail "oracle cannot see generated content" :expected lines :actual mine}

      (= lines mine)
      {:id (:id c) :group (:group c) :status :pass}

      :else
      {:id (:id c) :group (:group c) :status :fail :expected lines :actual mine})))

;; ---- report ----

(defn- pct [n d] (if (zero? d) 0 (js/Math.round (* 100 (/ n d)))))

;; nbb/ClojureScript has no clojure.core/format.
(defn- pad-right [s n] (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))
(defn- pad-left [s n] (let [s (str s)] (str (apply str (repeat (max 0 (- n (count s))) " ")) s)))

(let [{:keys [browser width ledger only]} (parse-args *command-line-args*)
      candidates (find-browsers browser)
      cases (cond->> (edn/read-string (fs/readFileSync "conformance/cases.edn" "utf8"))
              only (filter #(str/includes? (str (:id %)) only)))
      page (path/join (os/tmpdir) "kotoba-conformance-corpus.html")
      _ (fs/writeFileSync page (corpus-page cases width))
      [browser oracle]
      (loop [[b & more] candidates failures []]
        (if (nil? b)
          (throw (ex-info "no candidate browser produced a measurement block"
                          {:tried failures}))
          (let [r (try [b (run-browser! b page)]
                       (catch :default e (println (str "oracle unusable: " b " -- " (ex-message e))) nil))]
            (or r (recur more (conj failures b))))))
      _ (println (str "\noracle:  " browser "\nwidth:   " width "px\ncases:   " (count cases) "\n"))
      results (vec (map-indexed (fn [i c]
                                  (compare-case (get oracle (keyword (str "case-" i)) []) width c))
                                cases))
      scorable (remove #(= :unscorable (:status %)) results)
      passed (filter #(= :pass (:status %)) scorable)
      by-group (->> scorable
                    (group-by :group)
                    (sort-by key)
                    (mapv (fn [[g rs]]
                            [g (count (filter #(= :pass (:status %)) rs)) (count rs)])))]
  (doseq [r results]
    (println (str (pad-right (name (:status r)) 16)
                  (pad-right (str (:id r)) 48)
                  (if (= :pass (:status r))
                    ""
                    (str "want " (pr-str (:expected r)) " got " (pr-str (:actual r))
                         (when (:detail r) (str " error: " (:detail r))))))))
  (println)
  (doseq [[g p t] by-group]
    (println (str "  " (pad-right (name g) 20) (pad-left p 2) "/" (pad-left t 2)
                  (pad-left (pct p t) 5) "%")))
  (println)
  (println (str "TOTAL " (count passed) "/" (count scorable) " = " (pct (count passed) (count scorable)) "%"
                (let [u (count (filter #(= :unscorable (:status %)) results))]
                  (when (pos? u) (str "   (" u " unscorable, excluded)")))))
  (when ledger
    (let [entry {:conformance/oracle (last (str/split browser #"/"))
                 :conformance/width width
                 :conformance/total (count scorable)
                 :conformance/passed (count passed)
                 :conformance/pct (pct (count passed) (count scorable))
                 :conformance/unscorable (vec (sort (map :id (filter #(= :unscorable (:status %)) results))))
                 :conformance/by-group (into {} (map (fn [[g p t]] [g [p t]]) by-group))
                 :conformance/failing (vec (sort (map :id (remove #(= :pass (:status %)) scorable))))}]
      (fs/appendFileSync ledger (str (pr-str entry) "\n"))
      (println (str "\nappended to " ledger)))))
