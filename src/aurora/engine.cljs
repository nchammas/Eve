(ns aurora.engine
  (:require [aurora.core :as core]
            [aurora.util.xhr :as xhr]
            [aurora.util.async :as async]
            [clojure.walk :as walk]
            [cljs.core.match]
            [cljs.core.async.impl.protocols :as protos]
            [cljs.core.async :refer [put! chan sliding-buffer take! timeout]])
  (:require-macros [cljs.core.match.macros :refer [match]]
                   [dommy.macros :refer [node sel1 sel]]
                   [cljs.core.async.macros :refer [go]]
                   [aurora.macros :refer [filter-match]]))

(deftype MetaPrimitive [thing meta]
  IWithMeta
  (-with-meta [this meta] (set! (.-meta this) meta))

  IMeta
  (-meta [this] (.-meta this))

  IDeref
  (-deref [this] thing))

(meta (MetaPrimitive. 14234 {:path [:data 'foo 0]}))


(defn ->value [thing]
  (if (satisfies? IDeref thing)
    @thing
    thing))

;;[:data 'foo]
;;[:data 'foo 0]
;;[:data 'foo 2 0]
;;[:data 'foo :comments 0]

(def listener-loop (chan))
(def event-loop (chan))
(def commute-listener nil)

(defn commute [v]
  (println "[engine] Commuting: " (meta v))
  (let [path (-> v meta :path)
        v (if (seq? v)
            (with-meta (vec v) (meta v))
            v)
        v (walk/postwalk (fn [x]
                           (if (instance? MetaPrimitive x)
                             @x
                             x))
                         v)]
    (aset js/aurora.pipelines (first path) (if (next path)
                                             (assoc-in (aget js/aurora.pipelines (first path)) (rest path) v)
                                             v))
    (when-not (second path)
      (meta-walk v path))

    (put! event-loop :commute)))

(defn as-meta [thing path]
  (if-not (satisfies? IMeta thing)
    (MetaPrimitive. thing {:path path})
    thing))

(defn each [vs f]
  (if-let [path (-> vs meta :path (or []))]
    (with-meta (map f vs) (meta vs))
    (map f vs)))

(defn each-meta [vs f]
  (if-let [path (-> vs meta :path)]
    (each
     (with-meta
       (for [[i v] (map-indexed vector vs)]
         (as-meta v (conj path i)))
       (meta vs))
     f)
    (each vs f)))

(defn mget [thing path]
  (when-let [cur (get-in thing path)]
    (as-meta cur (into (-> thing meta :path (or [])) path))))

(defn rem [thing parent]
  (let [final (core/vector-remove parent (-> thing meta :path last))]
    (meta-walk final (-> parent meta :path))
    final))

(defn conj [p c]
  (cljs.core/conj p (with-meta c {:path (-> p meta :path (cljs.core/conj (count p)))})))

(def assoc (with-meta (fn assoc [p k v]
                        (if-not (satisfies? IMeta v)
                          (cljs.core/assoc p k v)
                          (cljs.core/assoc p k (with-meta v {:path (-> p meta :path (cljs.core/conj k))}))))
             {:desc "Add key/value"}))

(defn start-main-loop [main]
  (let [debounced (async/debounce event-loop 10)]
  (go
   (loop [run? true]
     (when run?
       (println "[engine] running at: " (.getTime (js/Date.)))
       (main)
       (put! listener-loop :done)
       (recur (<! debounced)))))))

(extend-protocol IAssociative
  List
  (-assoc [coll k v]
          (with-meta
            (apply list (assoc (with-meta (vec coll) (meta coll)) k v))
            (meta coll))))

(defn meta-walk [cur path]
  (when (and (not= nil cur)
             (satisfies? IMeta cur))
    (alter-meta! cur cljs.core/assoc :path path)
    (cond
     (list? cur) (doseq [[k v] (map-indexed vector cur)]
                     (meta-walk v (cljs.core/conj path k)))
     (map? cur) (doseq [[k v] cur]
                  (meta-walk v (cljs.core/conj path k)))
     (vector? cur) (doseq [[k v] (map-indexed vector cur)]
                     (meta-walk v (cljs.core/conj path k))))))

(defn exec-program [prog clear?]
  (when (or clear? (not js/aurora.pipelines))
    (set! js/aurora.pipelines (js-obj)))
  (doseq [[k v] (:data prog)
          :when (not (aget js/aurora.pipelines (str k)))]
    (meta-walk v [(str k)])
    (aset js/aurora.pipelines (str k) v))
  (put! event-loop false)
  (set! js/aurora.engine.event-loop (chan))
  (put! listener-loop false)
  (set! js/aurora.engine.listener-loop (chan))
  (go
   (let [pipes (<! (xhr/xhr [:post "http://localhost:8082/code"] {:code (pr-str (:pipes prog))}))]
     (.eval js/window pipes)
     (println "evaled: " (subs pipes 0 10))
     (start-main-loop (fn []
                        (let [main-fn (aget js/aurora.pipelines (str (:main prog)))
                              main-pipe (first (filter #(= (:main prog) (:name %)) (:pipes prog)))
                              vals (map #(aget js/aurora.pipelines (str %)) (:scope main-pipe))]
                          (apply main-fn vals))))

     )))


(exec-program '


              {:data {program

{:data {todos [{"todo" "Get milk" "done?" false}]
		state {"state" "all"}}

 :pipes [

		 {:name ->todo
		  :scope [todos current-todo]
		  :pipe [(match [current-todo]
						[{"editing?" true}] ["li.editing"
											 ["input" {"enter" (partial ->edit current-todo) "value" (current-todo "todo") "focused" true}]]
						:else ["li" {"class" (->done-class current-todo)}
							   ["input" {"checked" (current-todo "done?") "type" "checkbox" "click" (partial ->toggle-done current-todo)}]
							   ["label" {"dblclick" (partial ->editing current-todo)} (current-todo "todo")]
							   ["button" {"click" (partial ->rem todos current-todo)} ""]])]}

		 {:name ->active-todos
		  :scope [todos state]
		  :pipe [(match [(state "state")]
						["all"] todos
						["active"] (filter-match {"done?" false} todos)
						["completed"] (filter-match {"done?" true} todos))]}

		 {:name root
		  :scope [todos state]
		  :pipe [["div#todoapp"
				  ["header#header"
				   ["h1" "Todos"]
				   ["input#toggle-all" {"type" "checkbox" "click" (partial ->all-completed todos state) "checked" (state "all-toggle")}]
				   ["input#new-todo" {"enter" (partial ->add todos) "placeholder" "What needs to be done?"}]]
				  ["ul#todo-list"
				   (each (->active-todos todos state) (partial ->todo todos))]
				  ["div#footer"
				   ["span#todo-count" (->left todos)]
				   ["ul#filters"
					["li" ["a" {"click" (partial ->state state "all") "class" (->state-class state "all")} "All"]]
					["li" ["a" {"click" (partial ->state state "active") "class" (->state-class state "active")} "Active"]]
					["li" ["a" {"click" (partial ->state state "completed") "class" (->state-class state "completed")} "Completed"]]]
				   (->rem-completed-button todos)]]
				 (core/inject _PREV_)]}

		 {:name ->done-class
		  :scope [current-todo]
		  :pipe [(match [(current-todo "done?")]
						[true] "completed"
						:else "")]}

		 {:name ->set-done
		  :scope [state val current-todo]
		  :pipe [(assoc current-todo "done?" val)]}

		 {:name ->all-completed
		  :scope [todos state]
		  :pipe [(assoc state "all-toggle" (not (state "all-toggle")))
				 (commute _PREV_)
				 (each todos (partial ->set-done state (not (state "all-toggle"))))
				 (commute _PREV_)]}

		 {:name ->add
		  :scope [todos e]
		  :pipe [{"todo" (e "value")
				  "done?" false}
				 (conj todos _PREV_)
				 (commute _PREV_)]}

		 {:name ->editing
		  :scope [current-todo]
		  :pipe [(assoc current-todo "editing?" true)
				 (commute _PREV_)
				 ]}

		 {:name ->edit
		  :scope [current-todo e]
		  :pipe [(assoc current-todo "todo" (e "value"))
				 (assoc _PREV_ "editing?" false)
				 (commute _PREV_)]}

		 {:name ->toggle-done
		  :scope [current-todo]
		  :pipe [(match [current-todo]
						[{"done?" true}] false
						:else true)
				 (assoc current-todo "done?" _PREV_)
				 (commute _PREV_)]}

		 {:name ->rem
		  :scope [todos current-todo]
		  :pipe [(rem current-todo todos)
				 (commute _PREV_)]}

		 {:name ->rem-completed
		  :scope [todos]
		  :pipe [(filter-match {"done?" false} todos)
				 (commute _PREV_)]}

		 {:name ->rem-completed-button
		  :scope [todos current-todo]
		  :pipe [(count (filter-match {"done?" true} todos))
				 (match [_PREV_]
						[0] nil
						[cur] [:button#clear-completed {"click" (partial ->rem-completed todos)} "Clear completed (" cur ")"])]}

		 {:name ->left
		  :scope [todos]
		  :pipe [(filter-match {"done?" false} todos)
				 (count _PREV_)
				 (match [_PREV_]
						[1] (str "1 item left")
						[cur] (str cur " items left" ))]}

		 {:name ->state
		  :scope [state val]
		  :pipe [(assoc state "state" val)
				 (commute _PREV_)]}

		 {:name ->state-class
		  :scope [state val]
		  :pipe [(match [(state "state")]
						[val] "active"
						:else "")]}
		 ]

 :main root}

        state {"pipe" root
			   "step" 0
			   "prev" []
			   "dirty" true
			   "charts" {}}}
 :pipes [

		 {:name find-pipe
		  :scope [name]
		  :pipe [(-> (filter-match [cur name]
								   {:name cur}
								   (get-in program [:pipes]))
					 first)]}

         {:name show
          :pipe [(core/ctx! :app)
				 (if (state "dirty")
				   (do
					 (println "[editor] Executing dirty")
					 (core/!runner program))
				   (let [cur (find-pipe (state "pipe"))]
					 (println "[editor] re-drawing")
					 (core/root-inject
					  [:div#aurora
					   ;(->data)
					   (->pipeline cur)
					   (->workspace cur)
					   ])))

				 ]}


         {:name ->data
          :pipe [[:ul.data
                  (each-meta (program :data) ->data-rep)]]}

         {:name ->data-rep
          :scope [[k v]]
          :pipe [[:li (pr-str v)]]}

		 {:name ->match-pair
		  :scope [pipe [match action]]
		  :pipe [[:div.entry (pr-str match) (step-rep pipe action)]]}

		 {:name ->match-ui
		  :scope [match]
		  :pipe [[:div.match (pr-str (second match))
				  (each-meta (partition 2 (drop 2 match)) (partial ->match-pair pipe))]]}

		 {:name ->filter-match-ui
		  :scope [match]
		  :pipe [[:div.match "filter-match"]]}

		 {:name set-pipe
		  :scope [func]
		  :pipe [(update-in state ["prev"] conj (state "pipe"))
				 (assoc _PREV_ "pipe" func)
				 (assoc _PREV_ "step" 0)
				 (commute _PREV_)]}

		 {:name filter-in-scope
		  :scope [pipe args]
		  :pipe [
				 (set (:scope pipe))
				 (remove _PREV_ args)]}

		 {:name get-in-scope
		  :scope [pipe var depth]
		  :pipe [(if (= var (symbol "_PREV_"))
				   (js/aurora.transformers.editor.->step (:name pipe) (dec (:cur-step pipe)))
				   (-> (js/aurora.transformers.editor.->scope (:name pipe))
					   (get var)
					   (get-in depth))

						)
				 ]}

		 {:name ->invocation
		  :scope [func args pipe]
		  :pipe [(let [pipe? (find-pipe func)
					   data? (get-in program [:data func])
					   in-scope? ((set (:scope pipe)) func)
					   attrs (if pipe?
							   {"click" (partial set-pipe func)
								"class" "func pipeline"}
							   {})
					   prev (symbol "_PREV_")]
				   (match [pipe? data? in-scope? func]
						  [(_ :guard boolean) _ _ _] (if-let [desc (op-lookup func args pipe)]
													 [:div.func attrs desc]
													 [:div.func attrs "(" [:div (-> func str)] (each-meta (filter-in-scope pipe args) (partial step-rep pipe)) ")"])
						  [_ _ (_ :guard boolean) _] [:div.data  (pr-str (get-in-scope pipe func args))]
						  [_ _ _ 'partial] (->invocation (first args) (rest args) pipe)
						  [_ _ _ prev] [:div.prev.data (pr-str (js/aurora.transformers.editor.->step (:name pipe) (dec (:cur-step pipe))))]
						  :else (if-let [desc (op-lookup func args pipe)]
								  [:div.func attrs desc]
								  [:div.func attrs "(" [:div (-> func str)] (each-meta args (partial step-rep pipe)) ")"]))

				 )]}

		 {:name ensure-meta
		  :scope [thing path]
		  :pipe [(if (satisfies? IMeta thing)
				   (with-meta thing {:path path})
				   (js/aurora.engine.as-meta thing path))]}

		 {:name ->map-entry
		  :scope [pipe path [k v] class]
		  :pipe [[:li {"class" (str "entry " class)}
				  (step-rep pipe (ensure-meta k (cljs.core/conj path k :aurora.core/key)))
				  (step-rep pipe (ensure-meta v (cljs.core/conj path k)))]]
		  }

		 {:name op-lookup
		  :scope [op args pipe ]
		  :pipe [(match [op]
						['assoc] [:div.map "{" (for [[k v] (get-in-scope pipe (first args))]
												 (if (= k (second args))
												   (->map-entry pipe [] [k (nth args 2)] "assoc")
												   (->map-entry pipe [] [k v] ))) "}"]
						;(list "In " (->invocation (first args) nil pipe) " set " (str (second args) " to " (nth args 2)))

						['commute] (str "Replace")
						['core/inject] (str "To html")
						['conj] (let [cur (get-in-scope pipe (first args))
									  cnt (count cur)
									  cur (if (> cnt 2)
											(apply vector "..." (subvec cur (- cnt 3) cnt))
											cur)]

								  [:div.vector "[" (each cur pr-str) [:div.assoc (step-rep pipe (second args))] "]"]
								  )
						;(list "Append " (pr-str (second args)) " to " (->invocation (first args) nil pipe))
						:else nil)]}

		 {:name ->math-rep
		  :scope [form]
		  :pipe [(match [(core/type form) form]
						[:vector (["count" & r] :seq)] [:span.math-op.math-count "n"]
						[:vector (["sum" & r] :seq)] [:span.math-op [:span.math-sigma "Σ"] "x" [:sub "i"] ]
						[:vector _] [:div.math-expression (each-meta (interpose (first form) (rest form)) ->math-rep)]
						[:string "/"] [:div.math-divider]
						[:string _] [:span.math-op form]
						:else [:span.prim (str form)])]}

		 {:name set-step
		  :scope [i]
		  :pipe [(assoc state "step" i)
				 (commute _PREV_)]}

		 {:name ->steps-ui
		  :scope [pipe]
		  :pipe []}

		 {:name ->math
		  :scope [math-call]
		  :pipe [(rest math-call)
				 [:div.math (each _PREV_ ->math-rep)]
				 ]}

		 {:name program-commute
		  :scope [thing]
		  :pipe [(commute thing)
				 (assoc state "dirty" true)
				 (commute _PREV_)]}

		 {:name fill-scope
		  :scope [pipe struct step-num]
		  :pipe [(let [prev-sym (symbol "_PREV_")
					   prev-value (js/aurora.transformers.editor.->step (:name pipe) (dec step-num))
					   scope (js/aurora.transformers.editor.->scope (:name pipe))
					   scope (assoc scope prev-sym prev-value)]
				   (js/clojure.walk.postwalk-replace scope struct))]}

		 {:name chart-options
		  :scope [chart-ed]
		  :pipe [
				 (assoc-in state ["charts" (-> chart-ed meta :path) "options"] true)
				 (commute _PREV_)
				 ]
		  }

		 {:name chart-add-data
		  :scope [chart e]
		  :pipe [
				 (last (js/cljs.reader.read-string (.dataTransfer.getData e "path")))
				 (do (println _PREV_) _PREV_)
				 (assoc chart "values" _PREV_)
				 (program-commute _PREV_)
				 ]
		  }

		 {:name set-chart-option
		  :scope [chart option value]
		  :pipe [
				 (println chart)
				 (assoc chart option value)
				 (do (println _PREV_) _PREV_)
				 (program-commute _PREV_)
				 ]
		  }

		 {:name set-chart-type
		  :scope [chart v]
		  :pipe [
				 (println e)
				 (assoc chart "type" v)
				 (program-commute _PREV_)
				 ]
		  }

		 {:name ->chart-ed
		  :scope [pipe chart-call]
		  :pipe [(println (meta chart-call))
				 (let [chart-state ((state "charts")
									(-> chart-call meta :path))
					   chart-data (fill-scope pipe (second chart-call) (core/last-path chart-call))]
				   [:div.chart-ed
					[:div {;"click" (partial chart-options chart-call)
						   "dragover" (fn [e] (.preventDefault e))
						   "dragenter" (fn [e] (println "entered!") (.preventDefault e))
						   "drop" (partial chart-add-data (second chart-call))}
					 (js/aurora.transformers.chart.!chart-canvas  chart-data)]
					[:ul.chart-options
					 (for [t ["line" "pie" "bar" "donut"]]
					   [:li {"click" (partial set-chart-type (second chart-call) t) "selected" (= (chart-data "type") t)} t]
					   )
					 [:li {"selected" (chart-data "bezierCurve") "click" (partial set-chart-option (second chart-call) "bezierCurve" (not (chart-data "bezierCurve")))} "smooth"]

					 ]]
				   )

				  ]}

		 {:name modify-primitive
		  :scope [cur]
		  :pipe [(println "time to modify: " (meta cur) (assoc state "modifying" (-> cur meta :path)))
				 (when-let [path (-> cur meta :path)]
				 (commute (assoc state "modifying" path)))]}

		 {:name set-primitive
		  :scope [cur e]
		  :pipe [(println "set primitive!" (-> prim meta :path))
				 (cond
				  (string? @cur) (e "value")
				  (core/is-float? @cur) (js/parseFloat (e "value"))
				  (number? @cur) (js/parseInt (e "value"))
				  :else (e "value"))
				 (core/commute-path (-> cur meta :path) _PREV_)
				 (println "commuting state" (meta state))
				 (assoc state "dirty" true)
				 (commute (assoc _PREV_ "modifying" nil))]}

		 {:name primitive-or-editor
		  :scope [prim val class]
		  :pipe [(let [path (-> prim meta :path)]
				   (if (and path
							(= path (state "modifying")))
					 [:input.prim-editor {"enter" (partial set-primitive prim) :value @prim :focused true}]
					 [:div {"class" class "click" (partial modify-primitive prim)} val])
				   )]}

		 {:name drag-data
		  :scope [substep e]
		  :pipe [(.dataTransfer.setData e "path" (-> substep meta :path))]}

		 {:name step-rep
		  :scope [pipe substep]
		  :pipe [(let [prev (symbol "_PREV_")
					   not-sym (complement symbol)
					   cur (if (satisfies? IDeref substep)
							 @substep
							 substep)]
				 (match [(core/type substep) substep]
                        [:vector _] [:div.vector {"draggable" true "dragstart" (partial drag-data substep)} (each-meta substep (partial step-rep pipe))]
                        [:list (['match & r] :seq)] (->match-ui substep pipe)
						[:list (['filter-match & r] :seq)] (->filter-match-ui substep)
						[:list (['core/!math & r] :seq)] (->math substep)
						[:list (['core/!chart & r] :seq)] (->chart-ed pipe substep)
                        [:list ([func & r] :seq)] (if (symbol? func)
													(->invocation func r pipe)
													(each-meta substep (partial step-rep pipe)))
						[:seq _] (each-meta substep (partial step-rep pipe))
						[:map _] [:ul.map "{" (each substep (partial ->map-entry pipe (-> substep meta :path))) "}"]
						[:number _] (primitive-or-editor substep (str cur) "number")
						[:symbol _] (->invocation substep nil pipe)
                        [:string _] (primitive-or-editor substep cur "string")
                        [:keyword _] [:div.string (str cur)]
						[:fn _] [:div.fn "fn"]
						[:html _] [:div.html "html!"]
                        :else (pr-str cur)))]}

         {:name ->pipe-step
          :scope [pipe substep]
          :pipe [[:li {
					   "class" (let [i (core/last-path substep)]
								 (match [(state "step")]
										[i] "active"
										:else ""))}
				  (step-rep (assoc pipe :cur-step (core/last-path substep)) substep)
				  (when-let [cap (js/aurora.transformers.editor.->step (:name pipe) (core/last-path substep))]
					[:div.result (step-rep (assoc pipe :cur-step (core/last-path substep)) (ensure-meta cap [(symbol "_PREV_")]))])
				  ]]}

		 {:name ->backup
		  :scope [cur]
		  :pipe [(assoc state "prev" (vec (take (core/last-path cur) (state "prev"))))
				 (assoc _PREV_ "pipe" cur)
				 (commute _PREV_)]}

		 {:name ->prev-step
		  :scope [p]
		  :pipe [["li" {"click" (partial ->backup p)}]]}

         {:name ->pipeline
          :scope [pipe]
          :pipe [[:ul.breadcrumb
				  (each-meta (state "prev") ->prev-step)
                  ]]}

		 {:name ->workspace
		  :scope [pipe]
		  :pipe [[:ul.workspace
				  (when-let [cap (js/aurora.transformers.editor.->scope (:name pipe))]
					[:li.scope (for [[k v] cap]
								 (step-rep pipe (if (satisfies? IMeta v)
												  (with-meta v {:path [k]})
												  (js/aurora.engine.as-meta v [k]))))])
				  (each-meta (pipe :pipe) (partial ->pipe-step pipe))]]}

         ]
 :main show}




)
