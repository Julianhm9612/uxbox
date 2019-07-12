;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer
  (:require [lentes.core :as l]
            [uxbox.builtins.icons :as i]
            [uxbox.util.i18n :refer [tr]]
            [rumext.core :as mx :include-macros true]
            [uxbox.view.store :as st]
            [uxbox.view.data.viewer :as dv]
            [uxbox.view.ui.viewer.nav :refer [nav]]
            [uxbox.view.ui.viewer.canvas :refer [canvas]]
            [uxbox.view.ui.viewer.sitemap :refer [sitemap]]))

;; --- Refs

(defn- sort-pages
  [{:keys [pages] :as state}]
  (let [get-order #(get-in % [:metadata :order])]
    (assoc state :pages (->> (sort-by get-order pages)
                             (into [])))))

(def state-ref
  (-> (comp (l/select-keys [:flags :pages :project])
            (l/lens sort-pages))
      (l/derive st/state)))

;; --- Component

(defn- viewer-page-init
  [own]
  (let [[token] (::mx/args own)]
    (st/emit! (dv/initialize token))
    own))

(mx/defc viewer-page
  {:mixins [mx/static mx/reactive]
   :init viewer-page-init
   :key-fn vector}
  [token index id]
  (let [{:keys [project pages flags]} (mx/react state-ref)
        sitemap? (contains? flags :sitemap)]
    (when (seq pages)
      [:section.view-content
       (when sitemap?
         (sitemap project pages index))
       (nav flags)
       (canvas (if (nil? id)
                 (nth pages index)
                 (some #(= id (:id %)) pages)))])))
