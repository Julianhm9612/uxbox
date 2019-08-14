;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2019 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.viewport
  (:require
   [beicon.core :as rx]
   [goog.events :as events]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.workspace.canvas :refer [canvas]]
   [uxbox.main.ui.workspace.grid :refer [grid]]
   [uxbox.main.ui.workspace.ruler :refer [ruler]]
   [uxbox.main.ui.workspace.streams :as uws]
   [uxbox.main.ui.workspace.drawarea :refer [start-drawing]]

   [uxbox.main.ui.shapes :as uus]
   [uxbox.main.ui.workspace.drawarea :refer [draw-area]]
   [uxbox.main.ui.workspace.selection :refer [selection-handlers]]

   [uxbox.util.data :refer [parse-int]]
   [uxbox.util.components :refer [use-rxsub]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt])
  (:import goog.events.EventType))

;; --- Coordinates Widget

(mf/defc coordinates
  [{:keys [zoom] :as props}]
  (let [coords (some-> (use-rxsub uws/mouse-position)
                       (gpt/divide zoom)
                       (gpt/round 0))]
    [:ul.coordinates
     [:span {:alt "x"}
      (str "X: " (:x coords "-"))]
     [:span {:alt "y"}
      (str "Y: " (:y coords "-"))]]))

;; --- Cursor tooltip

(defn- get-shape-tooltip
  "Return the shape tooltip text"
  [shape]
  (case (:type shape)
    :icon "Click to place the Icon"
    :image "Click to place the Image"
    :rect "Drag to draw a Box"
    :text "Drag to draw a Text Box"
    :path "Click to draw a Path"
    :circle "Drag to draw a Circle"
    nil))

;; (mf/defc cursor-tooltip
;;   {:wrap [mf/wrap-memo]}
;;   [{:keys [tooltip]}]
;;   (let [coords (mf/deref refs/window-mouse-position)]
;;     [:span.cursor-tooltip
;;      {:style
;;       {:position "fixed"
;;        :left (str (+ (:x coords) 5) "px")
;;        :top (str (- (:y coords) 25) "px")}}
;;      tooltip]))

;; --- Selection Rect

(def ^:private handle-selrect
  (letfn [(update-state [state position]
            (let [id (get-in state [:workspace :current])
                  selrect (get-in state [:workspace id :selrect])]
              (if selrect
                (assoc-in state [:workspace id :selrect]
                          (dw/selection->rect (assoc selrect :stop position)))
                (assoc-in state [:workspace id :selrect]
                          (dw/selection->rect {:start position :stop position})))))

          (clear-state [state]
            (let [id (get-in state [:workspace :current])]
              (update-in state [:workspace id] dissoc :selrect)))]
    (reify
      ptk/WatchEvent
      (watch [_ state stream]
        (let [stoper (rx/filter #(or (dw/interrupt? %) (uws/mouse-up? %)) stream)]
          (rx/concat
           (rx/of (dw/deselect-all))
           (->> uws/mouse-position
                (rx/map (fn [pos] #(update-state % pos)))
                (rx/take-until stoper))
           (rx/of dw/select-shapes-by-current-selrect
                  clear-state)))))))

(mf/defc selrect
  {:wrap [mf/wrap-memo]}
  [{:keys [data] :as props}]
  (when data
    (let [{:keys [x1 y1 width height]} (geom/size data)]
      [:rect.selection-rect
       {:x x1
        :y y1
        :width width
        :height height}])))


;; --- Viewport Positioning

(def handle-viewport-positioning
  (letfn [(on-point [dom reference point]
            (let [{:keys [x y]} (gpt/subtract point reference)
                  cx (.-scrollLeft dom)
                  cy (.-scrollTop dom)]
              (set! (.-scrollLeft dom) (- cx x))
              (set! (.-scrollTop dom) (- cy y))))]
    (reify
      ptk/EffectEvent
      (effect [_ state stream]
        (let [stoper (rx/filter #(= ::finish-positioning %) stream)
              reference @uws/mouse-position
              dom (dom/get-element "workspace-viewport")]
          (-> (rx/take-until stoper uws/mouse-position)
              (rx/subscribe #(on-point dom reference %))))))))

;; --- Viewport

(mf/defc viewport
  [{:keys [page] :as props}]
  (let [{:keys [drawing-tool tooltip zoom flags edition] :as wst} (mf/deref refs/workspace)
        viewport-ref (mf/use-ref nil)
        tooltip (or tooltip (get-shape-tooltip drawing-tool))
        zoom (or zoom 1)]
    (letfn [(on-mouse-down [event]
              (dom/stop-propagation event)
              (let [ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uws/mouse-event :down ctrl? shift?)))
              (when (not edition)
                (if drawing-tool
                  (st/emit! (start-drawing drawing-tool))
                  (st/emit! :interrupt handle-selrect))))

            (on-context-menu [event]
              (dom/prevent-default event)
              (dom/stop-propagation event)
              (let [ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uws/mouse-event :context-menu ctrl? shift?))))

            (on-mouse-up [event]
              (dom/stop-propagation event)
              (let [ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uws/mouse-event :up ctrl? shift?))))

            (on-click [event]
              (dom/stop-propagation event)
              (let [ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uws/mouse-event :click ctrl? shift?))))

            (on-double-click [event]
              (dom/stop-propagation event)
              (let [ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:shift? shift?
                          :ctrl? ctrl?}]
                (st/emit! (uws/mouse-event :double-click ctrl? shift?))))

            (translate-point-to-viewport [pt]
              (let [viewport (mf/ref-node viewport-ref)
                    brect (.getBoundingClientRect viewport)
                    brect (gpt/point (parse-int (.-left brect))
                                     (parse-int (.-top brect)))]
                (gpt/subtract pt brect)))

            (on-key-down [event]
              (let [bevent (.getBrowserEvent event)
                    key (.-keyCode event)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:key key
                          :shift? shift?
                          :ctrl? ctrl?}]
                (when-not (.-repeat bevent)
                  (st/emit! (uws/keyboard-event :down key ctrl? shift?))
                  (when (kbd/space? event)
                    (st/emit! handle-viewport-positioning)
                    #_(st/emit! (dw/start-viewport-positioning))))))

            (on-key-up [event]
              (let [key (.-keyCode event)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:key key
                          :shift? shift?
                          :ctrl? ctrl?}]
                (when (kbd/space? event)
                  (st/emit! ::finish-positioning #_(dw/stop-viewport-positioning)))
                (st/emit! (uws/keyboard-event :up key ctrl? shift?))))

            (on-mouse-move [event]
              (let [pt (gpt/point (.-clientX event)
                                  (.-clientY event))
                    pt (translate-point-to-viewport pt)]
                ;; (prn "viewport:on-mouse-move" pt)
                (st/emit! (uws/->PointerEvent :viewport pt (kbd/ctrl? event) (kbd/shift? event)))))


                ;;     ;; ctrl? (kbd/ctrl? event)
                ;;     ;; shift? (kbd/shift? event)
                ;;     ;; event {:ctrl ctrl?
                ;;     ;;        :shift shift?
                ;;     ;;        :window-coords wpt
                ;;     ;;        :viewport-coords vpt}
                ;;     ]
                ;; #_(st/emit! (uws/pointer-event wpt vpt ctrl? shift?))))

            (on-mount []
              (prn "viewport.on-mount" (:id page))
              (let [
                    ;; key1 (events/listen js/document EventType.MOUSEMOVE on-mousemove)
                    key2 (events/listen js/document EventType.KEYDOWN on-key-down)
                    key3 (events/listen js/document EventType.KEYUP on-key-up)]
                (fn []
                  ;; (events/unlistenByKey key1)
                  (events/unlistenByKey key2)
                  (events/unlistenByKey key3))))]

      (mf/use-effect on-mount)
      ;; (prn "viewport.render" (:id page))

      [:*
       [:& coordinates {:zoom zoom}]
       #_[:div.tooltip-container
        (when tooltip
          [:& cursor-tooltip {:tooltip tooltip}])]
       [:svg.viewport {:width (* c/viewport-width zoom)
                       :height (* c/viewport-height zoom)
                       :ref viewport-ref
                       :class (when drawing-tool "drawing")
                       :on-context-menu on-context-menu
                       :on-click on-click
                       :on-double-click on-double-click
                       :on-mouse-move on-mouse-move
                       :on-mouse-down on-mouse-down
                       :on-mouse-up on-mouse-up}
        [:g.zoom {:transform (str "scale(" zoom ", " zoom ")")}
         (when page
           ;; (prn "selected:" (:selected wst))

           [:*
            (for [item (:canvas page)]
              [:& canvas {:key (:id item)
                          :id (:id item)
                          :x (:x item)
                          :y (:y item)
                          :width (:width item)
                          :height (:height item)}])

            (for [id (reverse (:shapes page))]
              [:& uus/shape-component {:id id :key id}])

            (when (seq (:selected wst))
              [:& selection-handlers {:wst wst}])

            (when-let [dshape (:drawing wst)]
              [:& draw-area {:shape dshape
                             :zoom (:zoom wst)
                             :modifiers (:modifiers wst)}])])



         (if (contains? flags :grid)
           [:& grid {:page page}])]
        (when (contains? flags :ruler)
          [:& ruler {:zoom zoom :ruler (:ruler wst)}])
        [:& selrect {:data (:selrect wst)}]]])))


#_(mf/def viewport
  :init
  (fn [own props]
    (assoc own ::viewport (mf/create-ref)))

  :did-mount
  (fn [own]
    (letfn [
            (translate-point-to-viewport [pt]
              (let [viewport (mf/ref-node (::viewport own))
                    brect (.getBoundingClientRect viewport)
                    brect (gpt/point (parse-int (.-left brect))
                                     (parse-int (.-top brect)))]
                (gpt/subtract pt brect)))

            (on-key-down [event]
              (let [bevent (.getBrowserEvent event)
                    key (.-keyCode event)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:key key
                          :shift? shift?
                          :ctrl? ctrl?}]
                (when-not (.-repeat bevent)
                  (st/emit! (uws/keyboard-event :down key ctrl? shift?))
                  (when (kbd/space? event)
                    (st/emit! handle-viewport-positioning)
                    #_(st/emit! (dw/start-viewport-positioning))))))

            (on-key-up [event]
              (let [key (.-keyCode event)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    opts {:key key
                          :shift? shift?
                          :ctrl? ctrl?}]
                (when (kbd/space? event)
                  (st/emit! ::finish-positioning #_(dw/stop-viewport-positioning)))
                (st/emit! (uws/keyboard-event :up key ctrl? shift?))))

            (on-mousemove [event]
              (let [wpt (gpt/point (.-clientX event)
                                   (.-clientY event))
                    vpt (translate-point-to-viewport wpt)
                    ctrl? (kbd/ctrl? event)
                    shift? (kbd/shift? event)
                    event {:ctrl ctrl?
                           :shift shift?
                           :window-coords wpt
                           :viewport-coords vpt}]
                (st/emit! (uws/pointer-event wpt vpt ctrl? shift?))))]

      (let [key1 (events/listen js/document EventType.MOUSEMOVE on-mousemove)
            key2 (events/listen js/document EventType.KEYDOWN on-key-down)
            key3 (events/listen js/document EventType.KEYUP on-key-up)]
        (assoc own
               ::key1 key1
               ::key2 key2
               ::key3 key3))))

  :will-unmount
  (fn [own]
    (events/unlistenByKey (::key1 own))
    (events/unlistenByKey (::key2 own))
    (events/unlistenByKey (::key3 own))
    (dissoc own ::key1 ::key2 ::key3))

  :mixins [mf/reactive]

  :render
  (fn [own {:keys [page] :as props}]
    (let [{:keys [drawing-tool tooltip zoom flags edition] :as wst} (mf/react refs/workspace)
          tooltip (or tooltip (get-shape-tooltip drawing-tool))
          zoom (or zoom 1)]
      (letfn [(on-mouse-down [event]
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uws/mouse-event :down ctrl? shift?)))
                (when (not edition)
                  (if drawing-tool
                    (st/emit! (start-drawing drawing-tool))
                    (st/emit! :interrupt handle-selrect))))
              (on-context-menu [event]
                (dom/prevent-default event)
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uws/mouse-event :context-menu ctrl? shift?))))
              (on-mouse-up [event]
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uws/mouse-event :up ctrl? shift?))))
              (on-click [event]
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uws/mouse-event :click ctrl? shift?))))
              (on-double-click [event]
                (dom/stop-propagation event)
                (let [ctrl? (kbd/ctrl? event)
                      shift? (kbd/shift? event)
                      opts {:shift? shift?
                            :ctrl? ctrl?}]
                  (st/emit! (uws/mouse-event :double-click ctrl? shift?))))]
        [:*
         [:& coordinates {:zoom zoom}]
         [:div.tooltip-container
          (when tooltip
            [:& cursor-tooltip {:tooltip tooltip}])]
         [:svg.viewport {:width (* c/viewport-width zoom)
                         :height (* c/viewport-height zoom)
                         :ref (::viewport own)
                         :class (when drawing-tool "drawing")
                         :on-context-menu on-context-menu
                         :on-click on-click
                         :on-double-click on-double-click
                         :on-mouse-down on-mouse-down
                         :on-mouse-up on-mouse-up}
          [:g.zoom {:transform (str "scale(" zoom ", " zoom ")")}
           (when page
             [:& canvas {:page page :wst wst}])

           (when page
             [:*
              (for [id (reverse (:shapes page))]
                [:& uus/shape-component {:id id :key id}])

              (when (seq (:selected wst))
                [:& selection-handlers {:wst wst}])

              (when-let [dshape (:drawing wst)]
                [:& draw-area {:shape dshape
                               :zoom (:zoom wst)
                               :modifiers (:modifiers wst)}])])



           (if (contains? flags :grid)
             [:& grid {:page page}])]
          (when (contains? flags :ruler)
            [:& ruler {:zoom zoom :ruler (:ruler wst)}])
          [:& selrect {:data (:selrect wst)}]]]))))
