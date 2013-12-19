"use strict";var crateAdminApp=angular.module("crateAdminApp",["ngRoute","sql","stats","common","overview","console","docs"]);crateAdminApp.config(["$routeProvider",function(a){a.when("/",{templateUrl:"views/empty_overview.html",controller:"OverviewController"}).when("/console",{templateUrl:"views/console.html",controller:"ConsoleController"}).when("/docs",{templateUrl:"views/docs.html",controller:"DocsController"}).otherwise({redirectTo:"/"})}]),crateAdminApp.run(["ClusterState",function(){}]),angular.module("sql",[]).factory("SQLQuery",["$http","$location","$log","$q",function(a,b,c,d){function e(a,b,c){this.stmt=a,this.rows=[],this.cols=[],this.rowCount=[],this.duration=0,this.error={message:"",code:0},this.failed=c,void 0!=b.error||1==this.failed?(this.failed=!0,this.error=b.error):(this.rows=b.rows,this.cols=b.cols,this.rowCount=b.rowCount,this.duration=b.duration)}var f=b.search().prefix||"";return e.prototype.status=function(){var a="",b=this.stmt.split(" "),d=b[0].toUpperCase();return d in{CREATE:"",DROP:""}&&(d=d+" "+b[1].toUpperCase()),a=0==this.failed?d+" OK ("+(this.duration/1e3).toFixed(3)+" sec)":d+" ERROR ("+(this.duration/1e3).toFixed(3)+" sec)",c.debug("Query status: "+a),a},e.execute=function(b,g){var h={stmt:b};void 0!=g&&(h.args=g);var i=d.defer(),j=i.promise;return j.success=function(a){return j.then(function(b){a(b)}),j},j.error=function(a){return j.then(null,function(b){a(b)}),j},a.post(f+"/_sql",h).success(function(a){i.resolve(new e(b,a,!1))}).error(function(a,d){c.debug("Got ERROR response from query: "+b+" with status: "+d),0==d&&(a={error:{message:"Connection error",code:0}}),i.reject(new e(b,a,!0))}),j},e}]),angular.module("stats",["sql"]).factory("ClusterState",["$http","$timeout","$location","$log","SQLQuery",function(a,b,c,d,e){function f(a){var b=0,c=[0,0,0];for(var d in a){b++;for(var e=0;3>e;e++)c[e]=c[e]+a[d].os.load_average[e]}for(var e;3>e;e++)c[e]=c[e]/b;return c}var g=c.search().prefix||"",h={green:"label-success",yellow:"label-warning",red:"label-danger","--":"label-default"},i={name:"--",status:"--",color_label:"label-default",load:["-.-","-.-","-.-"]},j=5e3,k=function(){e.execute("select sum(number_of_shards) from information_schema.tables").success(function(a){var b=0;a.rowCount>0&&(b=a.rows[0][0]),e.execute('select count(*), "primary", state from stats.shards group by "primary", state').success(function(a){var c=0,d=0;for(var e in a.rows)1==a.rows[e][1]&&a.rows[e][2]in{STARTED:"",RELOCATING:""}?c=a.rows[e][0]:"UNASSIGNED"==a.rows[e][2]&&(d=a.rows[e][0]);b>c?(i.status="red",i.color_label=h.red):d>0?(i.status="yellow",i.color_label=h.yellow):(i.status="green",i.color_label=h.green)}).error(function(){i.status="--",i.color_label=h["--"]})}).error(function(){i.status="--",i.color_label=h["--"]}),b(k,j)},l=function(){a({method:"GET",url:g+"/_nodes/stats?all=true"}).success(function(a){i.name=a.cluster_name,i.load=f(a.nodes)}).error(function(){i.name="--",i.load=["-.-","-.-","-.-"]}),b(l,j)};return k(),l(),{data:i}}]),angular.module("common",["stats"]).controller("StatusBarController",["$scope","$log","$location","ClusterState",function(a,b,c,d){a.$watch(function(){return d.data},function(b){a.cluster_state=b.status,a.cluster_name=b.name,a.cluster_color_label=b.color_label,a.load1="-.-"==b.load[0]?b.load[0]:b.load[0].toFixed(2),a.load5="-.-"==b.load[1]?b.load[1]:b.load[1].toFixed(2),a.load15="-.-"==b.load[2]?b.load[2]:b.load[2].toFixed(2)},!0);var e=c.search().prefix||"";a.docs_url=e+"/_plugin/docs"}]).controller("NavigationController",["$scope","$location","ClusterState",function(a,b,c){a.$watch(function(){return c.data},function(b){a.cluster_color_label_bar=b.color_label,("label-success"==b.color_label||"label-default"==b.color_label)&&(a.cluster_color_label_bar="")},!0),a.isActive=function(a){return a===b.path()}}]),angular.module("overview",["stats"]).controller("OverviewController",["$scope","$log","ClusterState",function(a,b,c){a.$watch(function(){return c.data},function(b){a.cluster_state=b.status,a.cluster_color_label=b.color_label,a.replicated_data="90%",a.available_data="100%",a.records_total=235e3,a.records_underreplicated=2e4,a.records_unavailable=0},!0)}]),angular.module("console",["sql"]).controller("ConsoleController",["$scope","$http","$location","SQLQuery","$log",function(a,b,c,d){a.statement="",a.rows=[],$("iframe").hide(),a.resultHeaders=[],a.renderTable=!1,a.error={},a.error.hide=!0;var e=Ladda.create(document.querySelector("button[type=submit]"));a.execute=function(){e.start(),d.execute(a.statement).success(function(b){e.stop(),a.error.hide=!0,a.renderTable=!0,a.resultHeaders=[];for(var c in b.cols)a.resultHeaders.push(b.cols[c]);a.rows=b.rows,a.status=b.status()}).error(function(b){e.stop(),a.error.hide=!1,a.renderTable=!1,a.error.message=b.error.message,a.status=b.status(),a.rows=[],a.resultHeaders=[]})}}]),angular.module("docs",[]).controller("DocsController",["$scope","$location",function(a,b){var c=b.search().prefix||"";a.url=c+"/_plugin/docs"}]);