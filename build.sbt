ProjectBuild.coreSettings("reactiveservices")


lazy val sysevents = Project(
  id = "core-sysevents",
  base = file("core-sysevents")
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
    auth,
    codec_binary % "compile;test->test"
  )
)

lazy val tcp_server = Project(
  id = "tcp-server",
  base = file("tcp-server"),
  dependencies = Seq(
    core % "compile;test->test",
    codec_binary % "compile;test->test"
  )
)

lazy val repl_server = Project(
  id = "repl-server",
  base = file("repl-server"),
  dependencies = Seq(
    core % "compile;test->test",
    codec_binary % "compile;test->test"
  )
)



lazy val auth_api = Project(
  id = "auth-api",
  base = file("auth-api"),
  dependencies = Seq(
    core % "compile;test->test"
  )
)

lazy val auth = Project(
  id = "auth",
  base = file("auth"),
  dependencies = Seq(
    sysevents % "compile;test->test",
    core % "compile;test->test",
    node % "compile;test->test",
    auth_api % "compile;test->test"
  )
)

lazy val auth_js = Project(
  id = "auth-js",
  base = file("auth-js"),
  dependencies = Seq(
    core % "compile;test->test",
    js % "compile;test->test"
  )
).enablePlugins(PlayScala, SbtWeb)





lazy val js = Project(
  id = "core-js",
  base = file("core-js"),
  dependencies = Seq(
    core % "compile;test->test",
    node % "compile;test->test"
  )
).enablePlugins(PlayScala, SbtWeb)


lazy val examples_counter_statics = Project(
  id = "examples-counter-node-statics",
  base = file("examples/counter/node-statics"),
  dependencies = Seq(
    core % "compile;test->test",
    node % "compile;test->test",
    js, auth_js
    //    rfx_node_api % "compile;test->test"
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
)

lazy val examples_counter_service = Project(
  id = "examples-counter-node-service",
  base = file("examples/counter/node-service"),
  dependencies = Seq(
  core,
  auth,
    node % "compile;test->test"
  )
).enablePlugins(JavaServerAppPackaging)
