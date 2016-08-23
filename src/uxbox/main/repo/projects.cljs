;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.repo.projects
  "A main interface for access to remote resources."
  (:require [beicon.core :as rx]
            [uxbox.config :refer (url)]
            [uxbox.main.repo.pages :as pages]
            [uxbox.main.repo.impl :refer (request send!)]))

(defmethod request :fetch/projects
  [type data]
  (let [url (str url "/projects")]
    (send! {:url url :method :get})))

(defmethod request :fetch/project
  [_ id]
  (let [url (str url "/projects/" id)]
    (send! {:url url :method :get})))

(defmethod request :fetch/project-by-token
  [_ token]
  (let [url (str url "/projects-by-token/" token)]
    (->> (send! {:url url :method :get})
         (rx/map (fn [response]
                   (update-in response [:payload :pages]
                              (fn [pages]
                                (mapv pages/decode-page pages))))))))

(defmethod request :create/project
  [_ data]
  (let [params {:url (str url "/projects")
                :method :post
                :body data}]
    (send! params)))

(defmethod request :delete/project
  [_ id]
  (let [url (str url "/projects/" id)]
    (send! {:url url :method :delete})))