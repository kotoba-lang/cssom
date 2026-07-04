(ns cssom.layout
  "Box-model + flexbox layout projection from a kotoba virtual DOM tree
   (kotoba.wasm.dom/tree) to renderer draw ops.

   Covers: padding/border/margin box model with min/max-width and
   content-box/border-box sizing; display:flex with flex-direction/
   flex-wrap/justify-content/align-items/gap; position:relative/absolute
   with z-index stacking; opacity (multiplicatively inherited); background/
   background-color; borders; overflow+scroll-top/scroll-left clipping;
   form-control value/checked/selected-option-label projection; text input
   caret/selection. Real hosts can still swap this for text shaping/WebGPU
   buffers etc — the draw-ops data boundary is unchanged.

   Moved out of kotoba-lang/wasm-ui into kotoba-lang/cssom (ADR-2607051140)."
  (:require [clojure.string :as str]))

(def default-theme
  {:font-size 14
   :line-height 20
   :padding 4
   :gap 4
   :fg "#e6ebf5"
   :bg "#121724"
   :button-bg "#1f2738"})

(defn- parse-int
  [x fallback]
  (cond
    (integer? x) x
    (number? x) (long x)
    (string? x) (or #?(:clj (try (Long/parseLong (re-find #"-?\d+" x))
                               (catch Exception _ nil))
                       :cljs (let [n (js/parseInt x 10)]
                               (when-not (js/isNaN n) n)))
                    fallback)
    :else fallback))

(defn- parse-dbl
  [x fallback]
  (cond
    (number? x) (double x)
    (string? x) (or #?(:clj (try (Double/parseDouble (str/trim x))
                               (catch Exception _ nil))
                       :cljs (let [n (js/parseFloat x)]
                               (when-not (js/isNaN n) n)))
                    fallback)
    :else fallback))

(defn- attr [node k] (get-in node [:attrs k]))
(defn- style [node k] (get-in node [:attrs (keyword "style" (name k))]))

(defn- listeners [node]
  (let [ls (:listeners node)]
    (cond
      (map? ls) (keys ls)
      (sequential? ls) ls
      (set? ls) (seq ls)
      :else nil)))

(defn- text-node? [node] (string? node))

(defn- text-size [font-size line-height padding text]
  (let [char-w (long (* 0.6 font-size))]
    {:w (+ (* (count text) char-w) (* 2 padding))
     :h (+ line-height (* 2 padding))}))

;; ---- per-node computed style bag ----

(defn- node-style [node theme]
  {:display (style node :display)
   :position (or (style node :position) "static")
   :left (style node :left)
   :top (style node :top)
   :z-index (parse-int (style node :z-index) 0)
   :width (style node :width)
   :height (style node :height)
   :min-width (style node :min-width)
   :max-width (style node :max-width)
   :box-sizing (or (style node :box-sizing) "content-box")
   :padding (parse-int (style node :padding) (:padding theme))
   :margin (parse-int (style node :margin) 0)
   :border-width (parse-int (style node :border-width) 0)
   :border-color (or (style node :border-color) "#000000")
   :background (or (style node :background) (style node :background-color))
   :color (style node :color)
   :font-size (style node :font-size)
   :opacity (parse-dbl (style node :opacity) 1.0)
   :justify-content (or (style node :justify-content) "flex-start")
   :align-items (or (style node :align-items) "stretch")
   :flex-direction (or (style node :flex-direction) "row")
   :flex-wrap (or (style node :flex-wrap) "nowrap")
   :gap (parse-int (style node :gap) (:gap theme))
   :pointer-events (style node :pointer-events)
   :overflow (attr node :overflow)
   :scroll-top (parse-int (attr node :scroll-top) 0)
   :scroll-left (parse-int (attr node :scroll-left) 0)})

(defn- style-passthrough [st]
  {:display (:display st)
   :position (:position st)
   :z-index (:z-index st)
   :pointer-events (:pointer-events st)
   :overflow (:overflow st)
   :scroll-top (:scroll-top st)
   :scroll-left (:scroll-left st)
   :min-width (:min-width st)
   :max-width (:max-width st)
   :box-sizing (:box-sizing st)
   :justify-content (:justify-content st)
   :align-items (:align-items st)
   :flex-wrap (:flex-wrap st)})

(defn- resolve-width
  [st avail]
  (let [base (parse-int (:width st) avail)
        base (if-let [mn (:min-width st)] (max base mn) base)
        base (if-let [mx (:max-width st)] (min base mx) base)]
    base))

(defn- content-inset
  [st]
  (+ (:padding st) (if (= "border-box" (:box-sizing st)) (:border-width st) 0)))

(defn- translate-ops
  [dx dy ops]
  (mapv (fn [op]
          (cond-> op
            (contains? op :x) (update :x + dx)
            (contains? op :y) (update :y + dy)))
        ops))

(defn- default-bg
  "User-agent-stylesheet-style background default: buttons get a raised
   default fill, main/span stay transparent, everything else gets the
   theme's panel background -- unless an explicit background/background-color
   style already won."
  [tag st theme]
  (or (:background st)
      (case tag
        :button (:button-bg theme)
        :main nil
        :span nil
        (:bg theme))))

(defn- border-ops
  [st x y w h opacity]
  (when (pos? (:border-width st))
    (let [bw (:border-width st)
          color (:border-color st)
          base {:draw/op :rect :border? true :color color :opacity opacity}]
      [(assoc base :edge :top :x x :y y :w w :h bw)
       (assoc base :edge :right :x (- (+ x w) bw) :y y :w bw :h h)
       (assoc base :edge :bottom :x x :y (- (+ y h) bw) :w w :h bw)
       (assoc base :edge :left :x x :y y :w bw :h h)])))

(defn- absolute? [theme child]
  (and (map? child) (= "absolute" (:position (node-style child theme)))))

(defn- partition-flow
  [theme children]
  (let [groups (group-by #(absolute? theme %) children)]
    {:in-flow (get groups false [])
     :out-of-flow (get groups true [])}))

;; ---- flexbox main-axis distribution / cross-axis alignment ----

(defn- place-main-axis
  [justify sizes gap container-size]
  (let [n (count sizes)]
    (cond
      (zero? n) []

      (= justify "space-between")
      (let [total (reduce + 0 sizes)
            free (max 0 (- container-size total))
            step (if (> n 1) (/ free (dec n)) 0)]
        (loop [i 0 pos 0 offsets []]
          (if (= i n)
            offsets
            (recur (inc i) (+ pos (nth sizes i) step) (conj offsets pos)))))

      (contains? #{"center" "flex-end"} justify)
      (let [total (+ (reduce + 0 sizes) (* gap (max 0 (dec n))))
            free (max 0 (- container-size total))
            lead (if (= justify "center") (quot free 2) free)]
        (loop [i 0 pos lead offsets []]
          (if (= i n)
            offsets
            (recur (inc i) (+ pos (nth sizes i) gap) (conj offsets pos)))))

      :else
      (loop [i 0 pos 0 offsets []]
        (if (= i n)
          offsets
          (recur (inc i) (+ pos (nth sizes i) gap) (conj offsets pos)))))))

(defn- cross-offset
  [align child-cross container-cross]
  (case align
    "center" (quot (- container-cross child-cross) 2)
    "flex-end" (- container-cross child-cross)
    0))

(defn- pack-rows
  "Greedily packs measured children (indices) into rows that fit within
   container-main; row-wrapping is only implemented for flex-direction:row."
  [main-sizes gap container-main]
  (loop [idx 0 cur [] cur-size 0 rows []]
    (if (= idx (count main-sizes))
      (if (seq cur) (conj rows cur) rows)
      (let [sz (nth main-sizes idx)
            next-size (if (seq cur) (+ cur-size gap sz) sz)]
        (if (and (seq cur) (> next-size container-main))
          (recur idx [] 0 (conj rows cur))
          (recur (inc idx) (conj cur idx) next-size rows))))))

(declare layout-node)

(defn- measure-child
  [theme content-w opacity inherited child]
  (let [child-avail (if (map? child) (resolve-width (node-style child theme) content-w) content-w)]
    (layout-node theme 0 0 child-avail opacity inherited child)))

(defn- layout-flex-wrap-row
  [theme cx cy cw opacity inherited st measured]
  (let [gap (:gap st)
        main-sizes (mapv #(:w (:box %)) measured)
        rows-idx (pack-rows main-sizes gap cw)
        row-cross-sizes (mapv (fn [idxs] (apply max 0 (mapv #(:h (:box (nth measured %))) idxs))) rows-idx)
        row-cross-offsets (loop [i 0 pos 0 offsets []]
                             (if (= i (count rows-idx))
                               offsets
                               (recur (inc i) (+ pos (nth row-cross-sizes i) gap) (conj offsets pos))))
        draws (mapcat
               (fn [idxs row-y]
                 (let [sizes (mapv #(nth main-sizes %) idxs)
                       offs (place-main-axis "flex-start" sizes gap cw)]
                   (mapcat (fn [child-idx off]
                             (let [m (nth measured child-idx)
                                   dx (+ cx off)
                                   dy (+ cy row-y)]
                               (translate-ops dx dy (:draw m))))
                           idxs offs)))
               rows-idx row-cross-offsets)
        total-cross (+ (reduce + 0 row-cross-sizes) (* gap (max 0 (dec (count rows-idx)))))]
    {:draws (vec draws) :main-total cw :cross-total total-cross}))

(defn- layout-flex
  [theme x y avail-width opacity inherited st node in-flow]
  (let [column? (= "column" (:flex-direction st))
        wrap? (and (not column?) (= "wrap" (:flex-wrap st)))
        w (resolve-width st avail-width)
        inset (content-inset st)
        cx (+ x (:margin st) inset)
        cy (+ y (:margin st) inset)
        cw (max 0 (- w (* 2 inset)))
        gap (:gap st)
        measured (mapv #(measure-child theme cw opacity inherited %) in-flow)]
    (if wrap?
      (let [{:keys [draws cross-total]} (layout-flex-wrap-row theme cx cy cw opacity inherited st measured)
            node-h (or (:height st) (+ cross-total (* 2 inset)))]
        {:box-w w :box-h node-h :draws draws})
      (let [main-sizes (mapv (fn [m] (if column? (:h (:box m)) (:w (:box m)))) measured)
            cross-sizes (mapv (fn [m] (if column? (:w (:box m)) (:h (:box m)))) measured)
            auto-cross (if (seq cross-sizes) (apply max 0 cross-sizes) 0)
            cross-content (or (if column? (:width st) (:height st)) auto-cross)
            auto-main (+ (reduce + 0 main-sizes) (* gap (max 0 (dec (count main-sizes)))))
            main-content (or (if column? (:height st) (:width st)) auto-main)
            offsets (place-main-axis (:justify-content st) main-sizes gap main-content)
            draws (mapcat
                   (fn [m off]
                     (let [child-cross (if column? (:w (:box m)) (:h (:box m)))
                           c-off (cross-offset (:align-items st) child-cross cross-content)
                           dx (if column? (+ cx c-off) (+ cx off))
                           dy (if column? (+ cy off) (+ cy c-off))]
                       (translate-ops dx dy (:draw m))))
                   measured offsets)
            node-w (if column? (+ cross-content (* 2 inset)) (+ main-content (* 2 inset)))
            node-h (if column? (+ main-content (* 2 inset)) (+ cross-content (* 2 inset)))
            node-w (if (:width st) w node-w)]
        {:box-w node-w :box-h node-h :draws (vec draws)}))))

;; ---- block (normal-flow) layout ----

(defn- layout-children-block
  [theme content-x content-y content-w opacity inherited children]
  (loop [remaining children y content-y draws [] height 0]
    (if-let [child (first remaining)]
      (let [child-margin (if (map? child) (:margin (node-style child theme)) 0)
            child-y (+ y child-margin)
            {:keys [box draw]} (layout-node theme (+ content-x child-margin) child-y content-w opacity inherited child)
            child-h (:h box)
            advance (+ child-margin child-h child-margin (:gap theme))]
        (recur (rest remaining) (+ y advance) (into draws draw) (+ height advance)))
      {:draw draws :h (max 0 (- height (:gap theme)))})))

(defn- layout-absolute-children
  [theme content-x content-y content-w opacity inherited children]
  (let [placed (mapv (fn [child]
                        (let [cst (node-style child theme)
                              left (or (:left cst) 0)
                              top (or (:top cst) 0)
                              cx (+ content-x left)
                              cy (+ content-y top)
                              m (layout-node theme cx cy content-w opacity inherited child)]
                          {:z (:z-index cst) :draw (:draw m)}))
                      children)
        sorted (sort-by :z placed)]
    (vec (mapcat :draw sorted))))

(defn- option-label
  [node value]
  (some (fn [child]
          (when (and (map? child) (= :option (:tag child))
                     (= (str value) (str (get-in child [:attrs :value]))))
            (->> (:children child) (filter string?) (str/join ""))))
        (:children node)))

(defn- layout-form-control
  [theme x y avail-width opacity st node]
  (let [tag (:tag node)
        w (resolve-width st avail-width)
        inset (content-inset st)
        h (or (:height st) (+ (:line-height theme) (* 2 inset)))
        value (attr node :value)
        checked (true? (attr node :checked))
        input-type (str/lower-case (str (or (attr node :type) "text")))
        control-text (case tag
                       :select (option-label node value)
                       :input (if (= "checkbox" input-type)
                                (if checked "[x]" "[ ]")
                                (str value))
                       (str value))
        text-op (when (seq (str control-text))
                  {:draw/op :text :control? true :node/id (:node/id node)
                   :x (+ x inset) :y (+ y inset) :text control-text :opacity opacity})
        selection-start (attr node :selection-start)
        selection-end (attr node :selection-end)
        sel-ops (when (and (= tag :input) selection-start selection-end)
                  (let [s (parse-int selection-start nil)
                        e (parse-int selection-end nil)]
                    (when (and s e)
                      (if (= s e)
                        [{:draw/op :text :caret? true :node/id (:node/id node) :caret s :w 1
                          :x x :y y :opacity opacity}]
                        [{:draw/op :text :selection? true :node/id (:node/id node)
                          :selection/start s :selection/end e
                          :w (max 1 (* (- e s) (long (* 0.6 (:font-size theme)))))
                          :x x :y y :opacity opacity}]))))
        semantic (merge {:draw/op :node :id (:node/id node) :tag tag :x x :y y :w w :h h
                         :class (attr node :class) :listeners (listeners node)
                         :opacity opacity :value value :checked checked}
                        (style-passthrough st))]
    {:box {:x x :y y :w w :h h}
     :draw (cond-> [semantic]
             text-op (conj text-op)
             sel-ops (into sel-ops))}))

(defn- layout-block
  [theme x y avail-width opacity inherited st node]
  (let [w (resolve-width st avail-width)
        inset (content-inset st)
        content-x (+ x (:margin st) inset)
        content-y (+ y (:margin st) inset)
        content-w (max 0 (- w (* 2 inset)))
        scroll-x (:scroll-left st)
        scroll-y (:scroll-top st)
        {:keys [in-flow out-of-flow]} (partition-flow theme (:children node))
        {:keys [draw h]} (layout-children-block theme (- content-x scroll-x) (- content-y scroll-y) content-w opacity inherited in-flow)
        explicit-h (:height st)
        node-h (or explicit-h (+ h (* 2 inset)))
        node-w w
        absolute-draws (layout-absolute-children theme content-x content-y content-w opacity inherited out-of-flow)
        border-draws (or (border-ops st x y node-w node-h opacity) [])
        bg (default-bg (:tag node) st theme)
        rect (when bg [{:draw/op :rect :x x :y y :w node-w :h node-h :color bg :tag (:tag node) :opacity opacity}])
        semantic [(merge {:draw/op :node :id (:node/id node) :tag (:tag node) :x x :y y :w node-w :h node-h
                          :class (attr node :class) :listeners (listeners node)
                          :opacity opacity}
                         (style-passthrough st))]
        clip? (and (:overflow st) (not= "visible" (:overflow st)))
        clip-push (when clip? [{:draw/op :clip :clip/op :push :node/id (:node/id node)
                                :x x :y y :w node-w :h node-h}])
        clip-pop (when clip? [{:draw/op :clip :clip/op :pop :node/id (:node/id node)
                               :x x :y y :w node-w :h node-h}])]
    {:box {:x x :y y :w node-w :h node-h}
     :draw (vec (concat border-draws rect semantic clip-push draw clip-pop absolute-draws))}))

(defn layout-node
  ([node] (layout-node default-theme 0 0 320 1.0 {:color (:fg default-theme) :font-size (:font-size default-theme)} node))
  ([theme x y avail-width opacity inherited node]
   (cond
     (nil? node)
     {:box {:x x :y y :w 0 :h 0} :draw []}

     (text-node? node)
     (let [{:keys [color font-size]} inherited
           {:keys [w h]} (text-size font-size (:line-height theme) (:padding theme) node)]
       {:box {:x x :y y :w (min avail-width w) :h h}
        :draw [{:draw/op :text :x (+ x (:padding theme)) :y (+ y (:padding theme))
                :text node :color color :font-size font-size :opacity opacity}]})

     (= :text (:node/type node))
     (recur theme x y avail-width opacity inherited (:text node))

     (= :element (:node/type node))
     (let [st (node-style node theme)]
       (if (= "none" (:display st))
         {:box {:x x :y y :w 0 :h 0} :draw []}
         (let [opacity (* opacity (:opacity st))
               color (or (:color st) (:color inherited))
               font-size (parse-int (:font-size st) (:font-size inherited))
               inherited (assoc inherited :color color :font-size font-size)
               tag (:tag node)]
           (cond
             (contains? #{:input :select :textarea} tag)
             (layout-form-control theme x y avail-width opacity st node)

             (= "flex" (:display st))
             (let [{:keys [box-w box-h draws]} (layout-flex theme x y avail-width opacity inherited st node (:children node))]
               {:box {:x x :y y :w box-w :h box-h}
                :draw (vec (concat
                            (or (border-ops st x y box-w box-h opacity) [])
                            (when-let [bg (default-bg tag st theme)]
                              [{:draw/op :rect :x x :y y :w box-w :h box-h :color bg :tag tag :opacity opacity}])
                            [(merge {:draw/op :node :id (:node/id node) :tag tag :x x :y y :w box-w :h box-h
                                     :class (attr node :class) :listeners (listeners node)
                                     :opacity opacity}
                                    (style-passthrough st))]
                            draws))})

             :else
             (layout-block theme x y avail-width opacity inherited st node)))))

     :else
     (recur theme x y avail-width opacity inherited (str node)))))

(defn draw-ops
  ([tree] (draw-ops tree {}))
  ([tree opts]
   (let [theme (merge default-theme (:theme opts))
         inherited {:color (:fg theme) :font-size (:font-size theme)}]
     (:draw (layout-node theme (or (:x opts) 0) (or (:y opts) 0) (or (:width opts) 320) 1.0 inherited tree)))))
