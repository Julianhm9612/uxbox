;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.canvas
  (:require
   [rumext.alpha :as mf]
   [lentes.core :as l]
   [uxbox.main.constants :as c]
   [uxbox.main.refs :as refs]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.shapes :as uus]
   [uxbox.main.ui.workspace.drawarea :refer [draw-area]]
   [uxbox.main.ui.workspace.selection :refer [selection-handlers]]
   [uxbox.main.ui.workspace.streams :as uws]
   [uxbox.util.data :refer [parse-int]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]))

(def selected-canvas
  (-> (l/key :selected-canvas)
      (l/derive refs/workspace)))

(mf/defc canvas
  [{:keys [id x y width height background] :as props}]
  (letfn [(on-double-click [event]
            (dom/prevent-default event)
            (st/emit! (dw/select-canvas id)))]
    (let [selected (mf/deref selected-canvas)
          selected? (= id selected)]
      [:rect.page-canvas
       {:x x
        :class (when selected? "selected")
        :y y
        :fill (or background "#ffffff")
        :width width
        :height height
        :on-double-click on-double-click}])))



