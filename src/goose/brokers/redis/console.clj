(ns goose.brokers.redis.console
  (:require [bidi.bidi :as bidi]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [goose.brokers.redis.api.dead-jobs :as dead-jobs]
            [goose.brokers.redis.api.enqueued-jobs :as enqueued-jobs]
            [goose.brokers.redis.api.scheduled-jobs :as scheduled-jobs]
            [goose.brokers.redis.cron :as periodic-jobs]
            [goose.defaults :as d]
            [hiccup.page :refer [html5 include-css include-js]]
            [hiccup.util :as hiccup-util]
            [ring.util.response :as response])
  (:import
    (java.lang Math)
    (java.util Date)))

(s/def ::page (s/and pos-int?))
(defn str->long
  [str]
  (if (= (type str) java.lang.Long)
    str
    (try (Long/parseLong str)
         (catch Exception _ :clojure.spec.alpha/invalid))))

(defn- layout [& components]
  (fn [title {:keys [prefix-route] :as data}]
    (html5 [:head
            [:meta {:charset "UTF-8"}]
            [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
            [:title title]
            (include-css (prefix-route "/css/style.css"))
            (include-js (prefix-route "/js/index.js"))]
           [:body
            (map (fn [c] (c data)) components)])))

(defn- header [{:keys [app-name prefix-route] :or {app-name ""}}]
  (let [short-app-name (if (> (count app-name) 20)
                         (str (subs app-name 0 17) "..")
                         app-name)]
    [:header
     [:nav
      [:div.nav-start
       [:div.goose-logo
        [:a {:href ""}
         [:img {:src (prefix-route "/img/goose-logo.png") :alt "goose-logo"}]]]
       [:div#menu
        [:a {:href (prefix-route "") :class "app-name"} short-app-name]
        [:a {:href (prefix-route "/enqueued")} "Enqueued"]]]]]))

(defn- stats-bar [{:keys [prefix-route] :as page-data}]
  [:main
   [:section.statistics
    (for [{:keys [id label route]} [{:id :enqueued :label "Enqueued" :route "/enqueued"}
                                    {:id :scheduled :label "Scheduled" :route "/scheduled"}
                                    {:id :periodic :label "Periodic" :route "/periodic"}
                                    {:id :dead :label "Dead" :route "/dead"}]]
      [:div.stat {:id id}
       [:span.number (str (get page-data id))]
       [:a {:href (prefix-route route)}
        [:span.label label]]])]])

(defn- sidebar [{:keys [prefix-route queues queue]}]
  [:div#sidebar
   [:h3 "Queues"]
   [:div.queue-list
    [:ul
     (for [q queues]
       [:a {:href  (prefix-route "/enqueued/queue/" q)
            :class (when (= q queue) "highlight")}
        [:li.queue-list-item q]])]]])

(defn- enqueued-jobs-table [jobs]
  [:table.job-table
   [:thead
    [:tr
     [:th.id-h "Id"]
     [:th.execute-fn-sym-h "Execute fn symbol"]
     [:th.args-h "Args"]
     [:th.enqueued-at-h "Enqueued-at"]]]
   [:tbody
    (for [{:keys [id execute-fn-sym args enqueued-at]} jobs]
      [:tr
       [:td.id id]
       [:td.execute-fn-sym execute-fn-sym]
       [:td.args (string/join ", " args)]
       [:td.enqueued-at (Date. ^Long enqueued-at)]])]])

(defn pagination-stats [first-page curr-page last-page]
  {:first-page first-page
   :prev-page  (dec curr-page)
   :curr-page  curr-page
   :next-page  (inc curr-page)
   :last-page  last-page})

(defn- pagination [{:keys [prefix-route queue page total-jobs]}]
  (let [{:keys [first-page prev-page curr-page
                next-page last-page]} (pagination-stats d/page page
                                                        (Math/ceilDiv total-jobs d/page-size))
        page-uri (fn [p] (prefix-route "/enqueued/queue/" queue "?page=" p))
        hyperlink (fn [page label visible? disabled? & class]
                    (when visible?
                      [:a {:class (conj class (when disabled? "disabled"))
                           :href  (page-uri page)} label]))
        single-page? (<= total-jobs d/page-size)]
    [:div
     (hyperlink first-page (hiccup-util/escape-html "<<") (not single-page?) (= curr-page first-page))
     (hyperlink prev-page prev-page (> curr-page first-page) false)
     (hyperlink curr-page curr-page (not single-page?) true "highlight")
     (hyperlink next-page next-page (< curr-page last-page) false)
     (hyperlink last-page (hiccup-util/escape-html ">>") (not single-page?) (= curr-page last-page))]))

(defn confirmation-dialog [{:keys [prefix-route queue]}]
  [:dialog {:class "purge-dialog"}
   [:div "Are you sure, you want to purge the " [:span.highlight queue] " queue?"]
   [:form {:action (prefix-route "/enqueued/queue/" queue)
           :method "post"
           :class  "dialog-btns"}
    [:input {:name "_method" :type "hidden" :value "delete"}]
    [:input {:name "queue" :value queue :type "hidden"}]
    [:input {:type "button" :value "Cancel" :class "btn btn-md btn-cancel cancel"}]
    [:input {:type "submit" :value "Confirm" :class "btn btn-danger btn-md"}]]])

(defn- enqueued-page-view [{:keys [jobs total-jobs] :as data}]
  [:div.redis-enqueued-main-content
   [:h1 "Enqueued Jobs"]
   [:div.content
    (sidebar data)
    [:div.right-side
     [:div.pagination
      (pagination data)]
     (enqueued-jobs-table jobs)
     (when (> total-jobs 0)
       [:div.bottom
        (confirmation-dialog data)
        [:button {:class "btn btn-danger btn-lg purge-dialog-show"} "Purge"]])]]])

(defn jobs-size [redis-conn]
  (let [queues (enqueued-jobs/list-all-queues redis-conn)
        enqueued (reduce (fn [total queue]
                           (+ total (enqueued-jobs/size redis-conn queue))) 0 queues)
        scheduled (scheduled-jobs/size redis-conn)
        periodic (periodic-jobs/size redis-conn)
        dead (dead-jobs/size redis-conn)]
    {:enqueued  enqueued
     :scheduled scheduled
     :periodic  periodic
     :dead      dead}))

(defn enqueued-page-data
  [redis-conn queue page]
  (let [page (if (s/valid? ::page (str->long page))
               (str->long page)
               d/page)
        start (* (- page 1) d/page-size)
        end (- (* page d/page-size) 1)

        queues (enqueued-jobs/list-all-queues redis-conn)
        queue (or queue (first queues))
        total-jobs (enqueued-jobs/size redis-conn queue)
        jobs (enqueued-jobs/get-by-range redis-conn queue start end)]
    {:queues     queues
     :page       page
     :queue      queue
     :jobs       jobs
     :total-jobs total-jobs}))

(defn home-page [{:keys                     [prefix-route]
                  {:keys [app-name broker]} :console-opts}]
  (let [view (layout header stats-bar)
        data (jobs-size (:redis-conn broker))]
    (response/response (view "Home" (assoc data :app-name app-name
                                                :prefix-route prefix-route)))))
(defn purge-queue [{{:keys [broker]} :console-opts
                    {:keys [queue]}  :params
                    :keys            [prefix-route]}]
  (enqueued-jobs/purge (:redis-conn broker) queue)
  (response/redirect (prefix-route "/enqueued")))

(defn enqueued-page [{:keys                     [prefix-route]
                      {:keys [app-name broker]} :console-opts
                      {:keys [page]}            :params
                      {:keys [queue]}           :route-params}]
  (let [view (layout header enqueued-page-view)
        data (enqueued-page-data (:redis-conn broker) queue page)]
    (response/response (view "Enqueued" (assoc data :app-name app-name
                                                    :prefix-route prefix-route)))))

(defn- load-css [_]
  (-> "css/style.css"
      response/resource-response
      (response/header "Content-Type" "text/css")))

(defn- load-img [_]
  (-> "img/goose-logo.png"
      response/resource-response
      (response/header "Content-Type" "image/png")))

(defn- load-js [_]
  (-> "js/index.js"
      response/resource-response
      (response/header "Content-Type" "text/javascript")))

(defn- redirect-to-home-page [{:keys [prefix-route]}]
  (response/redirect (prefix-route "/")))

(defn- not-found [_]
  (response/not-found "<div> Not found </div>"))

(defn routes [route-prefix]
  [route-prefix [["" redirect-to-home-page]
                 ["/" home-page]
                 ["/enqueued" {""                 enqueued-page
                               ["/queue/" :queue] [[:get enqueued-page]
                                                   [:delete purge-queue]]}]
                 ["/css/style.css" load-css]
                 ["/img/goose-logo.png" load-img]
                 ["/js/index.js" load-js]
                 [true not-found]]])

(defn handler [_ {:keys                                        [uri request-method]
                  {:keys [route-prefix] :or {route-prefix ""}} :console-opts
                  :as                                          req}]
  (let [{page-handler :handler
         route-params :route-params} (-> route-prefix
                                         routes
                                         (bidi/match-route uri {:request-method request-method}))]
    (-> req
        (assoc :route-params route-params)
        page-handler)))
