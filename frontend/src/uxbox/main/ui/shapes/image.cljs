;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes.image
  (:require [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.main.geom :as geom]
            [uxbox.main.refs :as refs]
            [uxbox.main.store :as st]
            [uxbox.main.ui.shapes.common :as common]
            [uxbox.main.ui.shapes.attrs :as attrs]
            [uxbox.main.data.images :as udi]
            [uxbox.util.data :refer [classnames normalize-props]]
            [uxbox.util.geom.matrix :as gmt]
            [rumext.core :as mx :include-macros true]))

;; --- Refs

(defn image-ref
  [id]
  (-> (l/in [:images id])
      (l/derive st/state)))

;; --- Image Component

(declare image-shape)

(mx/defcs image-component
  {:mixins [mx/static mx/reactive]
   :init (fn [own]
                 ;; TODO: maybe do it conditionally
                 ;; (only fetch when it's not fetched)
                 (when-let [id (-> own ::mx/props first :image)]
                   (st/emit! (udi/fetch-image id)))
                 own)}
  [own {:keys [id image] :as shape}]
  (let [modifiers (mx/react (refs/selected-modifiers id))
        selected (mx/react refs/selected-shapes)
        image (mx/react (image-ref image))
        selected? (contains? selected id)
        on-mouse-down #(common/on-mouse-down % shape selected)
        shape (assoc shape
                     :modifiers modifiers
                     :image image)]
    (when image
      [:g.shape {:class (when selected? "selected")
                 :on-mouse-down on-mouse-down}
       (image-shape shape)])))

;; --- Image Shape

(mx/defc image-shape
  {:mixins [mx/static]}
  [{:keys [id x1 y1 image modifiers] :as shape}]
  (let [{:keys [width height]} (geom/size shape)
        {:keys [resize displacement]} modifiers

        xfmt (cond-> (gmt/matrix)
               resize (gmt/multiply resize)
               displacement (gmt/multiply displacement))

        moving? (boolean displacement)
        props {:x x1 :y y1
               :id (str "shape-" id)
               :preserve-aspect-ratio "none"
               :class (classnames :move-cursor moving?)
               :xlink-href (:url image)
               :transform (str xfmt)
               :width width
               :height height}
        attrs (merge props (attrs/extract-style-attrs shape))]
    [:> :image (normalize-props attrs)]))
