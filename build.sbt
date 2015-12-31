ProjectBuild.coreSettings("reactiveservices")


lazy val utils = Project(
  id = "core-utils",
  base = file("core-utils")
)


lazy val sysevents = Project(
  id = "core-sysevents",
  base = file("core-sysevents"),
  dependencies = Seq(
    utils % "compile;test->test"
  )
)


lazy val core = Project(
  id = "core-services",
  base = file("core-services"),
  dependencies = Seq(
    sysevents % "compile;test->test"
  )
)


lazy val node = Project(
  id = "core-node",
  base = file("core-node"),
  dependencies = Seq(
    sysevents % "compile;test->test",
    core % "compile;test->test"
  )
)

lazy val codec_binary = Project(
  id = "core-codec-binary",
  base = file("core-codec-binary"),
  dependencies = Seq(
    core % "compile;test->test"
  )
)
lazy val websocket_server = Project(
  id = "websocket-server",
  base = file("websocket-server"),
  dependencies = Seq(
    core % "compile;test->test",
    node % "compile;test->test",
    auth,
    codec_binary % "compile;test->test"
  )
)


lazy val auth = Project(
  id = "auth",
  base = file("auth"),
  dependencies = Seq(
    sysevents % "compile;test->test",
    core % "compile;test->test",
    node % "compile;test->test"
  )
)



lazy val js = Project(
  id = "core-js",
  base = file("core-js"),
  dependencies = Seq(
    core % "compile;test->test",
    node % "compile;test->test"
  )
).enablePlugins(SbtWeb)



/** Examples: Counter **/


lazy val examples_counter_statics = Project(
  id = "examples-counter-node-statics",
  base = file("examples/counter/node-statics"),
  dependencies = Seq(
    core % "compile;test->test",
    node % "compile;test->test",
    js
  )
).enablePlugins(PlayScala, SbtWeb)

lazy val examples_counter_websocket = Project(
  id = "examples-counter-node-websocket",
  base = file("examples/counter/node-websocket"),
  dependencies = Seq(
    core % "compile;test->test",
    node,
    auth,
    websocket_server
  )
).enablePlugins(JavaServerAppPackaging)

lazy val examples_counter_service = Project(
  id = "examples-counter-node-service",
  base = file("examples/counter/node-service"),
  dependencies = Seq(
  core,
  auth,
    node % "compile;test->test"
  )
).enablePlugins(JavaServerAppPackaging)




/** Examples: Stocks **/

lazy val examples_stocks_statics = Project(
  id = "examples-stocks-node-statics",
  base = file("examples/stocks/node-statics"),
  dependencies = Seq(
    core % "compile;test->test",
    node % "compile;test->test",
    js
  )
).enablePlugins(PlayScala, SbtWeb)

lazy val examples_stocks_websocket = Project(
  id = "examples-stocks-node-websocket",
  base = file("examples/stocks/node-websocket"),
  dependencies = Seq(
    core % "compile;test->test",
    node,
    auth,
    websocket_server
  )
).enablePlugins(JavaServerAppPackaging)

lazy val examples_stocks_engine = Project(
  id = "examples-stocks-node-engine",
  base = file("examples/stocks/node-engine"),
  dependencies = Seq(
    core,
    auth,
    node % "compile;test->test"
  )
).enablePlugins(JavaServerAppPackaging)

lazy val examples_stocks_pricesource = Project(
  id = "examples-stocks-node-pricesource",
  base = file("examples/stocks/node-pricesource"),
  dependencies = Seq(
    core,
    auth,
    node % "compile;test->test"
  )
).enablePlugins(JavaServerAppPackaging)
