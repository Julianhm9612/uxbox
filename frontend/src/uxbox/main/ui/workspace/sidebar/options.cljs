;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options
  (:require
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.core :as mx :include-macros true]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.shapes :as uds]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.attrs :refer [shape-default-attrs]]
   [uxbox.main.ui.workspace.sidebar.options.circle-measures :as options-circlem]
   [uxbox.main.ui.workspace.sidebar.options.fill :as options-fill]
   [uxbox.main.ui.workspace.sidebar.options.icon-measures :as options-iconm]
   [uxbox.main.ui.workspace.sidebar.options.image-measures :as options-imagem]
   [uxbox.main.ui.workspace.sidebar.options.interactions :as options-interactions]
   [uxbox.main.ui.workspace.sidebar.options.page :as options-page]
   [uxbox.main.ui.workspace.sidebar.options.rect-measures :as options-rectm]
   [uxbox.main.ui.workspace.sidebar.options.stroke :as options-stroke]
   [uxbox.main.ui.workspace.sidebar.options.text :as options-text]
   [uxbox.util.data :as data]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.router :as r]))

;; --- Constants

(def ^:private +menus-map+
  {:icon [::icon-measures ::fill ::stroke ::interactions]
   :rect [::rect-measures ::fill ::stroke ::interactions]
   :path [::fill ::stroke ::interactions]
   :circle [::circle-measures ::fill ::stroke ::interactions]
   :text [::fill ::text ::interactions]
   :image [::image-measures ::interactions]
   :group [::fill ::stroke ::interactions]
   ::page [::page-measures ::page-grid-options]})

(def ^:private +menus+
  [{:name "Size, position & rotation"
    :id ::icon-measures
    :icon i/infocard
    :comp options-iconm/icon-measures-menu}
   {:name "Size, position & rotation"
    :id ::image-measures
    :icon i/infocard
    :comp options-imagem/image-measures-menu}
   {:name "Size, position & rotation"
    :id ::rect-measures
    :icon i/infocard
    :comp options-rectm/rect-measures-menu}
   {:name "Size, position & rotation"
    :id ::circle-measures
    :icon i/infocard
    :comp options-circlem/circle-measures-menu}
   {:name "Fill"
    :id ::fill
    :icon i/fill
    :comp options-fill/fill-menu}
   {:name "Stroke"
    :id ::stroke
    :icon i/stroke
    :comp options-stroke/stroke-menu}
   {:name "Text"
    :id ::text
    :icon i/text
    :comp options-text/text-menu}
   {:name "Interactions"
    :id ::interactions
    :icon i/action
    :comp options-interactions/interactions-menu}
   {:name "Page settings"
    :id ::page-measures
    :icon i/page
    :comp options-page/measures-menu}
   {:name "Grid settings"
    :id ::page-grid-options
    :icon i/grid
    :comp options-page/grid-options-menu}])

(def ^:private +menus-by-id+
  (data/index-by-id +menus+))

;; --- Options

(mx/defcs options
  {:mixins [mx/static (mx/local)]
   :key-fn #(pr-str (:id %1))}
  [{:keys [::mx/local] :as own} shape]
  (let [menus (get +menus-map+ (:type shape ::page))
        contained-in? (into #{} menus)
        active (:menu @local (first menus))]
    [:div {}
     (when (> (count menus) 1)
       [:ul.element-icons {}
        (for [menu-id (get +menus-map+ (:type shape ::page))]
          (let [menu (get +menus-by-id+ menu-id)
                selected? (= active menu-id)]
            [:li#e-info {:on-click #(swap! local assoc :menu menu-id)
                         :key (str "menu-" (:id menu))
                         :class (when selected? "selected")}
             (:icon menu)]))])
     (when-let [menu (get +menus-by-id+ active)]
       ((:comp menu) menu shape))]))

(def selected-shape-ref
  (letfn [(getter [state]
            (let [selected (get-in state [:workspace :selected])]
              (when (= 1 (count selected))
                (get-in state [:shapes (first selected)]))))]
    (-> (l/lens getter)
        (l/derive st/state))))

(mx/defc options-toolbox
  {:mixins [mx/static mx/reactive]}
  []
  (let [shape (->> (mx/react selected-shape-ref)
                   (merge shape-default-attrs))
        close #(st/emit! (udw/toggle-flag :element-options))]
    [:div.elementa-options.tool-window {}
     [:div.tool-window-bar {}
      [:div.tool-window-icon {} i/options]
      [:span {} (tr "ds.element-options")]
      [:div.tool-window-close {:on-click close} i/close]]
     [:div.tool-window-content {}
      [:div.element-options {}
       (options shape)]]]))
