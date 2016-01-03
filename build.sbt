import com.typesafe.sbt.pgp.PgpKeys.pgpSigningKey

lazy val root = project.in(file(".")).
  aggregate(utils, sysevents, core, node, websocket_server, auth, codec_binary, js).
  settings(Build.settings("reactiveservices", publishToSonatype = false))

lazy val utils = Project(
  id = "core-utils",
  base = file("core-utils")
)


lazy val sysevents = Project(
  id = "core-sysevents",
  base = file("core-sysevents"),
  dependencies = Seq(
    utils
  )
)


lazy val core = Project(
  id = "core-services",
  base = file("core-services"),
  dependencies = Seq(
    sysevents
  )
)


lazy val node = Project(
  id = "core-node",
  base = file("core-node"),
  dependencies = Seq(
    sysevents ,
    core
  )
)

lazy val codec_binary = Project(
  id = "core-codec-binary",
  base = file("core-codec-binary"),
  dependencies = Seq(
    core
  )
)
lazy val websocket_server = Project(
  id = "websocket-server",
  base = file("websocket-server"),
  dependencies = Seq(
    core ,
    node ,
    auth,
    codec_binary
  )
)


lazy val auth = Project(
  id = "auth",
  base = file("auth"),
  dependencies = Seq(
    sysevents ,
    core ,
    node
  )
)



lazy val js = Project(
  id = "core-js",
  base = file("core-js"),
  dependencies = Seq(
    core ,
    node
  )
).enablePlugins(SbtWeb)

