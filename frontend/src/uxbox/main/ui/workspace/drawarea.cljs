;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.drawarea
  "Draw interaction and component."
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.data.shapes :as ds]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes :as shapes]
   [uxbox.main.ui.workspace.streams :as uws]
   [uxbox.main.workers :as uwrk]
   [uxbox.util.math :as mth]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.path :as path]
   [uxbox.util.geom.point :as gpt]))

;; --- Events

(declare handle-drawing)
(declare handle-drawing-generic)
(declare handle-drawing-path)
(declare handle-drawing-free-path)
(declare handle-finish-drawing)

(defn start-drawing
  [object]
  (let [id (gensym "drawing")]
    (reify
      ptk/UpdateEvent
      (update [_ state]
        (update-in state [:workspace :drawing-lock] #(if (nil? %) id %)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [lock (get-in state [:workspace :drawing-lock])]
          (if (= lock id)
            (rx/merge (->> stream
                           (rx/filter #(= % handle-finish-drawing))
                           (rx/take 1)
                           (rx/map (fn [_] #(update % :workspace dissoc :drawing-lock))))
                      (rx/of (handle-drawing object)))
            (rx/empty)))))))

(defn- conditional-align [point align?]
  (if align?
    (uwrk/align-point point)
    (rx/of point)))

;; TODO: maybe this should be a simple function
(defn handle-drawing
  [shape]
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of
       (if (= :path (:type shape))
         (if (:free shape)
           (handle-drawing-free-path shape)
           (handle-drawing-path shape))
         (handle-drawing-generic shape))))))

(defn- handle-drawing-generic
  [shape]
  (letfn [(initialize-drawing [state point]
            (let [pid (get-in state [:workspace :current])
                  shape (get-in state [:workspace pid :drawing])
                  shape (geom/setup shape {:x1 (:x point)
                                           :y1 (:y point)
                                           :x2 (+ (:x point) 2)
                                           :y2 (+ (:y point) 2)})]
              (assoc-in state [:workspace pid :drawing] (assoc shape ::initialized? true))))

          ;; TODO: this is a new approach for resizing, when all the
          ;; subsystem are migrated to the new resize approach, this
          ;; function should be moved into uxbox.main.geom ns.
          (resize-shape [shape point lock?]
            (if (= (:type shape) :circle)
              (let [rx (mth/abs (- (:x point) (:cx shape)))
                    ry (mth/abs (- (:y point) (:cy shape)))]
                (if lock?
                  (assoc shape :rx rx :ry ry)
                  (assoc shape :rx rx :ry rx)))
              (let [width (- (:x point) (:x1 shape))
                    height (- (:y point) (:y1 shape))
                    proportion (:proportion shape 1)]
                (assoc shape
                       :x2 (+ (:x1 shape) width)
                       :y2 (if lock?
                             (+ (:y1 shape) (/ width proportion))
                             (+ (:y1 shape) height))))))

          (update-drawing [state point lock?]
            (let [pid (get-in state [:workspace :current])]
              (update-in state [:workspace pid :drawing] resize-shape point lock?)))]

    (reify
      ptk/WatchEvent
      (watch [_ state stream]
        (let [pid (get-in state [:workspace :current])
              zoom (get-in state [:workspace pid :zoom])
              flags (get-in state [:workspace pid :flags])
              align? (refs/alignment-activated? flags)

              stoper (->> (rx/filter #(or (uws/mouse-up? %) (= % :interrupt)) stream)
                          (rx/take 1))

              mouse (->> uws/mouse-position
                         (rx/mapcat #(conditional-align % align?))
                         (rx/with-latest vector uws/mouse-position-ctrl))]
          (rx/concat
           (->> uws/mouse-position
                (rx/take 1)
                (rx/mapcat #(conditional-align % align?))
                (rx/map (fn [pt] #(initialize-drawing % pt))))
           (->> mouse
                (rx/map (fn [[pt ctrl?]] #(update-drawing % pt ctrl?)))
                (rx/take-until stoper))
           (rx/of handle-finish-drawing)))))))

(defn handle-drawing-path
  [shape]
  (letfn [(stoper-event? [{:keys [type shift] :as event}]
             (or (= event :interrupt)
                 (and (uws/mouse-event? event)
                      (or (and (= type :double-click) shift)
                          (= type :context-menu)))
                 (and (uws/keyboard-event? event)
                      (= type :down)
                      (= 13 (:key event)))))

          (initialize-drawing [state point]
            (let [pid (get-in state [:workspace :current])]
              (-> state
                  (assoc-in [:workspace pid :drawing :segments] [point point])
                  (assoc-in [:workspace pid :drawing ::initialized?] true))))

          (insert-point-segment [state point]
            (let [pid (get-in state [:workspace :current])]
              (update-in state [:workspace pid :drawing :segments] (fnil conj []) point)))

          (update-point-segment [state index point]
            (let [pid (get-in state [:workspace :current])
                  segments (count (get-in state [:workspace pid :drawing :segments]))
                  exists? (< -1 index segments)]
              (cond-> state
                exists? (assoc-in [:workspace pid :drawing :segments index] point))))

          (remove-dangling-segmnet [state]
            (let [pid (get-in state [:workspace :current])]
              (update-in state [:workspace pid :drawing :segments] #(vec (butlast %)))))]
    (reify
      ptk/WatchEvent
      (watch [_ state stream]
        (let [pid (get-in state [:workspace :current])
              zoom (get-in state [:workspace pid :zoom])
              flags (get-in state [:workspace pid :flags])
              align? (refs/alignment-activated? flags)

              last-point (volatile! @uws/mouse-position)

              stoper (->> (rx/filter stoper-event? stream)
                          (rx/take 1))

              mouse (->> (rx/sample 10 uws/mouse-position)
                         (rx/mapcat #(conditional-align % align?)))

              points (->> stream
                          (rx/filter uws/mouse-click?)
                          (rx/filter #(false? (:shift %)))
                          (rx/with-latest vector mouse)
                          (rx/map second))

              counter (rx/merge (rx/scan #(inc %) 1 points) (rx/of 1))
              stream' (->> mouse
                          (rx/with-latest vector uws/mouse-position-ctrl)
                          (rx/with-latest vector counter)
                          (rx/map flatten))


              imm-transform #(vector (- % 7) (+ % 7) %)
              immanted-zones (vec (concat
                                   (map imm-transform (range 0 181 15))
                                   (map (comp imm-transform -) (range 0 181 15))))

              align-position (fn [angle pos]
                               (reduce (fn [pos [a1 a2 v]]
                                         (if (< a1 angle a2)
                                           (reduced (gpt/update-angle pos v))
                                           pos))
                                       pos
                                       immanted-zones))]

          (rx/merge
           (rx/of #(initialize-drawing % @last-point))

           (->> points
                (rx/take-until stoper)
                (rx/map (fn [pt]
                          #(insert-point-segment % pt))))
           (rx/concat
            (->> stream'
                 (rx/map (fn [[point ctrl? index :as xxx]]
                           (let [point (if ctrl?
                                         (as-> point $
                                           (gpt/subtract $ @last-point)
                                           (align-position (gpt/angle $) $)
                                           (gpt/add $ @last-point))
                                         point)]
                             #(update-point-segment % index point))))
                 (rx/take-until stoper))
            (rx/of remove-dangling-segmnet
                   handle-finish-drawing))))))))

(defn- handle-drawing-free-path
  [shape]
  (letfn [(stoper-event? [{:keys [type shift] :as event}]
             (or (= event :interrupt)
                 (and (uws/mouse-event? event) (= type :up))))

          (initialize-drawing [state]
            (let [pid (get-in state [:workspace :current])]
              (assoc-in state [:workspace pid :drawing ::initialized?] true)))

          (insert-point-segment [state point]
            (let [pid (get-in state [:workspace :current])]
              (update-in state [:workspace pid :drawing :segments] (fnil conj []) point)))

          (simplify-drawing-path [state tolerance]
            (let [pid (get-in state [:workspace :current])]
              (update-in state [:workspace pid :drawing :segments] path/simplify tolerance)))]
    (reify
      ptk/WatchEvent
      (watch [_ state stream]
        (let [pid (get-in state [:workspace :current])
              zoom (get-in state [:workspace pid :zoom])
              flags (get-in state [:workspace pid :flags])
              align? (refs/alignment-activated? flags)

              stoper (->> (rx/filter stoper-event? stream)
                          (rx/take 1))

              mouse (->> (rx/sample 10 uws/mouse-position)
                         (rx/mapcat #(conditional-align % align?)))]
          (rx/concat
           (rx/of initialize-drawing)
           (->> mouse
                (rx/map (fn [pt] #(insert-point-segment % pt)))
                (rx/take-until stoper))
           (rx/of #(simplify-drawing-path % 0.3)
                  handle-finish-drawing)))))))

(def handle-finish-drawing
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (let [pid (get-in state [:workspace :current])
            shape (get-in state [:workspace pid :drawing])]
        (if (::initialized? shape)
          (let [resize-mtx (get-in state [:workspace pid :modifiers (:id shape) :resize])
                shape (cond-> shape
                        resize-mtx (geom/transform resize-mtx))]
            (rx/of
             ;; Remove the stalled modifiers
             ;; TODO: maybe a specific event for "clear modifiers"
             #(update-in % [:workspace pid :modifiers] dissoc (:id shape))

             ;; Unselect the drawing tool
             ;; TODO; maybe a specific event for clear draw-tool
             #(update-in % [:workspace pid] dissoc :drawing :drawing-tool)

             ;; Add & select the cred shape to the workspace
             (dw/add-shape shape)
             (dw/select-first-shape)))
          (rx/of #(update-in % [:workspace pid] dissoc :drawing :drawing-tool)))))))

(def close-drawing-path
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace :current])]
        (assoc-in state [:workspace pid :drawing :close?] true)))))

;; --- Components

(declare generic-draw-area)
(declare path-draw-area)

(mf/defc draw-area
  [{:keys [zoom shape modifiers] :as props}]
  (if (= (:type shape) :path)
    [:& path-draw-area {:shape shape}]
    [:& generic-draw-area {:shape (assoc shape :modifiers modifiers)
                           :zoom zoom}]))

(mf/defc generic-draw-area
  [{:keys [shape zoom]}]
  (let [{:keys [x1 y1 width height]} (geom/selection-rect shape)]
    [:g
     (shapes/render-shape shape)
     [:rect.main {:x x1 :y y1
                  :width width
                  :height height
                  :stroke-dasharray (str (/ 5.0 zoom) "," (/ 5 zoom))
                  :style {:stroke "#333"
                          :fill "transparent"
                          :stroke-opacity "1"}}]]))

(mf/defc path-draw-area
  [{:keys [shape] :as props}]
  (letfn [(on-click [event]
            (dom/stop-propagation event)
            (st/emit! (dw/set-tooltip nil)
                      close-drawing-path
                      :interrupt))
          (on-mouse-enter [event]
            (st/emit! (dw/set-tooltip "Click to close the path")))
          (on-mouse-leave [event]
            (st/emit! (dw/set-tooltip nil)))]
    (when-let [{:keys [x y] :as segment} (first (:segments shape))]
      [:g
       (shapes/render-shape shape)
       (when-not (:free shape)
         [:circle.close-bezier
          {:cx x
           :cy y
           :r 5
           :on-click on-click
           :on-mouse-enter on-mouse-enter
           :on-mouse-leave on-mouse-leave}])])))
