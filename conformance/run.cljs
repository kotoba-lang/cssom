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
        "--debug-geometry" (recur (rest args) (assoc out :debug-geometry true))
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
        // Text INSIDE an atomic inline (a button's label, a select's
        // options) belongs to that control's own formatting context, not
        // to the line box being compared. Skipped on both sides -- see
        // engine-lines' matching containment filter -- so the comparison
        // measures one inline formatting context rather than two.
        if (node.parentElement &&
            node.parentElement.closest('img,input,button,select,textarea')) continue;
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
      // The GEOMETRY axis: every element's own box, relative to the case
      // root. The line axis answers 'what ended up on which line'; this
      // answers 'how big is each box and where does it sit' -- the
      // question that hid colspan (a spanning cell is alone on its row
      // either way) and a button label's vertical centering.
      var boxes = [];
      var els = root.querySelectorAll('*');
      var rootRect = root.getBoundingClientRect();
      for (var j = 0; j < els.length; j++) {
        var el = els[j];
        var r = el.getBoundingClientRect();
        boxes.push({ tag: el.tagName.toLowerCase(),
                     x: r.left - rootRect.left, y: r.top - rootRect.top,
                     w: r.width, h: r.height });
      }
      out[root.id] = { words: words, boxes: boxes };
    }
    // The oracle's OWN character width, measured rather than guessed: the
    // page is monospace, so one Range over a known-length string gives the
    // exact per-character advance this browser uses. Handing that back lets
    // the engine side wrap against the same metrics (see engine-lines'
    // :measure-text), which is what makes WRAPPING cases comparable at all
    // -- otherwise every wrap point differs by the ratio between this
    // engine's 0.6-em approximation and the real font, and the corpus can
    // only ever contain text short enough never to wrap.
    // A per-character advance TABLE, measured in the oracle, for normal
    // and bold. Not a single average: measured here, this system's
    // `monospace` face is fixed-pitch at 7.00px in regular but its BOLD
    // variant is proportional -- a 40-char run of 'M' reports 11.05px per
    // character while the real bold word `manual` reports 7.94. A single
    // probe character is therefore a 40%-wrong proxy for bold text, which
    // is what made every <b> box disagree (b 0/11) and looked like an
    // engine bug until it was measured directly.
    var probe = document.createElement('span');
    probe.style.cssText = 'font-family:monospace;font-size:14px;white-space:pre';
    document.body.appendChild(probe);
    var advances = { normal: {}, bold: {} };
    ['normal', 'bold'].forEach(function (weight) {
      probe.style.fontWeight = weight;
      for (var code = 32; code <= 126; code++) {
        var ch = String.fromCharCode(code);
        probe.textContent = new Array(21).join(ch);
        advances[weight][code] = probe.getBoundingClientRect().width / 20;
      }
    });
    probe.remove();
    out['__advances__'] = advances;
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

(defn- engine-ops
  "One layout pass through the real pipeline: htmldom parse -> cssom.core
   cascade -> cssom.layout draw-ops, at the harness width.

   Two theme settings, both of which remove something the comparison
   cannot legitimately judge rather than helping the engine:

   - `:measure-text` is fed the ORACLE's own measured per-character
     advance (see the measurement script's `__char_width__` probe) through
     the engine's existing host hook. Without it every wrap point differs
     by the constant ratio between this engine's 0.6-em approximation and
     the real font, and the corpus could only hold text short enough never
     to wrap. Where to BREAK is still entirely the engine's decision.
   - `:padding`/`:gap` are this engine's own theme (every box gets a 4px
     inset, every row a 4px gap) -- a host styling choice, not CSS. Left
     at their defaults they narrow the content width by 16px per nested
     box, scoring the theme instead of the layout."
  [{:keys [html css]} width char-w]
  (let [;; The wrapper carries the SAME declarations the browser page sets
        ;; on `.kotoba-case`. Without them the engine never saw the
        ;; container's `line-height: 20px` and fell back to its own
        ;; `normal` (1.2em) rule, so an <h1> got a 33px line box where the
        ;; browser -- which inherits the explicit 20px -- reports 20. That
        ;; was a harness asymmetry being scored as an engine error.
        doc (html/parse-into-document
             (str "<div id=\"root\" style=\"font-size: 14px; line-height: 20px\">" html "</div>"))
        ;; apply-cascade runs even with no author CSS: it is also what folds
        ;; a `style="..."` attribute's :style-inline into the :style/* attrs
        ;; cssom.layout actually reads, so skipping it would silently drop
        ;; every inline style in the corpus.
        doc (css/apply-cascade doc (css/parse-rules (or css "")))
        [_ doc] (dom/consume-ops doc)]
    (layout/draw-ops (dom/tree doc)
                     {:width width
                      :theme {:padding 0
                              :gap 0
                              :measure-text (fn [text font-size weight & _]
                                              (let [advance (if (= "bold" weight) (:bold char-w) (:normal char-w))]
                                                (* (/ (or font-size 14) 14)
                                                   (reduce + 0 (map advance (str text))))))}})))

(defn- engine-lines
  "cssom.layout's own answer, in the same shape the oracle's is read into.

   `char-w` is the ORACLE's own measured per-character advance (see the
   measurement script's `__char_width__` probe), threaded in through the
   engine's existing `:measure-text` theme hook -- the same hook a real
   host uses to make wrap decisions agree with how it will actually paint.
   Supplying it here is not a thumb on the scale: it removes the one
   difference the comparison cannot legitimately judge (this engine has no
   glyph shaping, so its 0.6-em approximation disagrees with any real font
   by a constant factor) and leaves the actual question -- WHERE the engine
   decides to break -- fully on the engine.

   Each `:text` draw-op is split back into words positioned by this
   engine's own width model, because that is the granularity the browser
   side is measured at; the op's vertical span is `[y, y + font-size]`,
   which is exactly the em box the real hosts paint into (dom-gpu draws at
   `y + font-size`, the baseline). Splitting per word also means a wrapped
   line compares correctly rather than as one blob."
  [{:keys [html css]} width char-w]
  (let [ops (engine-ops {:html html :css css} width char-w)
        ;; Mirror of the oracle script's own `closest(...)` skip: a form
        ;; control's or replaced box's INNER text is its own formatting
        ;; context. Done geometrically here because draw-ops carry no
        ;; parentage -- a text op inside an atomic box's rect is that box's
        ;; content.
        atomic-boxes (filterv #(and (= :node (:draw/op %))
                                    (contains? #{:img :input :button :select :textarea} (:tag %)))
                              ops)
        inside-atomic? (fn [op]
                         (some (fn [b]
                                 (and (>= (:x op) (:x b)) (<= (:x op) (+ (:x b) (:w b)))
                                      (>= (:y op) (:y b)) (<= (:y op) (+ (:y b) (:h b)))))
                               atomic-boxes))
        text-ops (->> ops
                      (filter #(= :text (:draw/op %)))
                      (remove inside-atomic?))
        word-w (fn [text fs weight]
                 (let [advance (if (= "bold" weight) (:bold char-w) (:normal char-w))]
                   (* (/ (or fs 14) 14) (reduce + 0 (map advance (str text))))))]
    (->> text-ops
         (mapcat (fn [op]
                   (let [fs (:font-size op 14)]
                     (loop [words (str/split (str (:text op)) #"(?=\s)|(?<=\s)")
                            x (:x op)
                            out []]
                       (if-let [w (first words)]
                         (recur (rest words) (+ x (word-w w fs (:font-weight op)))
                                (if (str/blank? w)
                                  out
                                  (conj out {:text w :left x
                                             :top (:y op) :bottom (+ (:y op) fs)})))
                         out)))))
         cluster-lines)))


;; ---- comparison ----

(def ^:private geometry-tolerance-px
  "How far a box may differ before the geometry axis calls it a mismatch.
   Not zero: both sides now wrap against the SAME measured character
   advance, so text-derived widths land within a pixel, but sub-pixel
   rounding is real on the browser side (fractional device pixels) and this
   engine works in whole pixels throughout."
  2)

(defn- geometry-agreement
  "Matches each element box between the two sides by tag and occurrence
   order -- both sides walk the same document, so the Nth `<td>` on one
   side is the Nth `<td>` on the other -- and reports how many agree
   within geometry-tolerance-px on all four of x/y/w/h.

   Matching by tag+occurrence rather than by injected ids keeps the corpus
   readable (cases stay plain HTML a person can eyeball) at the cost of
   being wrong if one side drops an element entirely; that shows up as a
   count mismatch, which is reported rather than silently zipped away."
  [oracle-boxes engine-boxes]
  (let [by-tag (fn [boxes] (group-by :tag boxes))
        o (by-tag oracle-boxes)
        e (by-tag engine-boxes)
        tags (distinct (concat (keys o) (keys e)))
        close? (fn [a b] (<= (abs (- (or a 0) (or b 0))) geometry-tolerance-px))]
    (reduce (fn [acc tag]
              (let [os (get o tag []) es (get e tag [])
                    pairs (map vector os es)
                    agree (count (filter (fn [[ob eb]]
                                           (and (close? (:x ob) (:x eb))
                                                (close? (:y ob) (:y eb))
                                                (close? (:w ob) (:w eb))
                                                (close? (:h ob) (:h eb))))
                                         pairs))
                    ;; WHICH dimension disagrees, and by how much: a tail of
                    ;; mismatches is far easier to attribute from "always h,
                    ;; always +4" than from a list of failing cases.
                    deltas (for [[ob eb] pairs
                                 dim [:x :y :w :h]
                                 :when (not (close? (get ob dim) (get eb dim)))]
                             {:tag tag :dim dim
                              :delta (- (or (get eb dim) 0) (or (get ob dim) 0))})]
                (-> acc
                    (update :total + (max (count os) (count es)))
                    (update :agree + agree)
                    (update :deltas into deltas)
                    (update :by-tag update tag (fnil (fn [[a t]] [(+ a agree) (+ t (max (count os) (count es)))]) [0 0]))
                    (cond-> (not= (count os) (count es))
                      (update :missing conj tag)))))
            {:total 0 :agree 0 :missing [] :by-tag {} :deltas []}
            tags)))

(defn- engine-boxes
  "Element boxes from the engine's own `:node` draw-ops, relative to the
   root box, in the same shape the oracle reports."
  [ops]
  (let [nodes (filterv #(= :node (:draw/op %)) ops)
        root (first (filter #(= :div (:tag %)) nodes))
        rx (:x root 0) ry (:y root 0)]
    (->> nodes
         (remove #(identical? % root))
         (remove #(= :document (:tag %)))
         (mapv (fn [op] {:tag (name (:tag op))
                         :x (- (:x op) rx) :y (- (:y op) ry)
                         :w (:w op) :h (:h op)})))))

(defn- engine-render
  "Both axes from one layout pass: the line structure and the element
   boxes."
  [c width char-w]
  {:lines (engine-lines c width char-w)
   :boxes (engine-boxes (engine-ops c width char-w))})

(defn- compare-case [oracle-data width char-w c]
  (let [oracle-words (:words oracle-data)
        lines (cluster-lines oracle-words)
        rendered (try (engine-render c width char-w)
                      (catch :default e {:error (ex-message e)}))
        mine (if (:error rendered) rendered (:lines rendered))
        geo (when-not (:error rendered)
              (geometry-agreement (:boxes oracle-data) (:boxes rendered)))]
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
      {:id (:id c) :group (:group c) :status :unscorable :geo geo
       :detail "oracle cannot see generated content" :expected lines :actual mine}

      (= lines mine)
      {:id (:id c) :group (:group c) :status :pass :geo geo
       :oracle-boxes (:boxes oracle-data) :engine-boxes (:boxes rendered)}

      :else
      {:id (:id c) :group (:group c) :status :fail :geo geo
       :expected lines :actual mine})))

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
      advances (:__advances__ oracle)
      char-w {:normal (fn [ch] (get-in advances [:normal (keyword (str (.charCodeAt ch 0)))] 8.4))
              :bold (fn [ch] (get-in advances [:bold (keyword (str (.charCodeAt ch 0)))] 8.4))}
      _ (println (str "metrics: per-character advance table measured in the oracle ("
                      (count (:normal advances)) " chars x normal/bold)\n"))
      results (vec (map-indexed (fn [i c]
                                  (compare-case (get oracle (keyword (str "case-" i)) []) width char-w c))
                                cases))
      scorable (remove #(= :unscorable (:status %)) results)
      passed (filter #(= :pass (:status %)) scorable)
      by-group (->> scorable
                    (group-by :group)
                    (sort-by key)
                    (mapv (fn [[g rs]]
                            [g (count (filter #(= :pass (:status %)) rs)) (count rs)])))]
  (when (:debug-geometry (parse-args *command-line-args*))
    (doseq [r results :when (seq (:oracle-boxes r))]
      (println "GEO" (:id r))
      (println "  oracle:" (pr-str (mapv (juxt :tag :x :y :w :h) (:oracle-boxes r))))
      (println "  engine:" (pr-str (mapv (juxt :tag :x :y :w :h) (:engine-boxes r))))))
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
  (let [geos (keep :geo results)
        boxes-total (reduce + 0 (map :total geos))
        boxes-agree (reduce + 0 (map :agree geos))
        clean (count (filter #(and (:geo %) (pos? (:total (:geo %)))
                                   (= (:total (:geo %)) (:agree (:geo %))))
                             results))
        with-boxes (count (filter #(pos? (:total (:geo % {:total 0}))) results))]
    (println (str "GEOMETRY  " boxes-agree "/" boxes-total " element boxes agree within "
                  geometry-tolerance-px "px  (" (pct boxes-agree boxes-total) "%)"))
    (println (str "          " clean "/" with-boxes " cases with every box in agreement"))
    (let [per-tag (reduce (fn [acc g]
                            (reduce (fn [acc [tag [a t]]]
                                      (update acc tag (fnil (fn [[a0 t0]] [(+ a0 a) (+ t0 t)]) [0 0])))
                                    acc (:by-tag g)))
                          {} geos)]
      (let [all-deltas (mapcat :deltas geos)
            by-dim (->> all-deltas
                        (group-by (juxt :tag :dim))
                        (map (fn [[[tag dim] ds]]
                               [tag dim (count ds)
                                (let [sorted (sort (map :delta ds))]
                                  (nth sorted (quot (count sorted) 2)))]))
                        (sort-by (fn [[_ _ n _]] (- n))))]
        (println "          worst (tag, dimension, count, median delta engine-oracle):")
        (doseq [[tag dim n med] (take 12 by-dim)]
          (println (str "            " (pad-right tag 8) (pad-right (name dim) 3)
                        (pad-left n 4) "  " (if (pos? med) (str "+" med) med)))))
      (println "          worst tags (agreeing/total):")
      (doseq [[tag [a t]] (->> per-tag (sort-by (fn [[_ [a t]]] (- a t))) (take 10))
              :when (< a t)]
        (println (str "            " (pad-right tag 12) a "/" t))))
    (println))
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
                 :conformance/failing (vec (sort (map :id (remove #(= :pass (:status %)) scorable))))
                 :conformance/geometry-boxes-agree (reduce + 0 (map :agree (keep :geo results)))
                 :conformance/geometry-boxes-total (reduce + 0 (map :total (keep :geo results)))}]
      (fs/appendFileSync ledger (str (pr-str entry) "\n"))
      (println (str "\nappended to " ledger)))))
