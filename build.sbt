import com.typesafe.sbt.pgp.PgpKeys.pgpSigningKey
import play.sbt.PlayScala

lazy val root = project.in(file(".")).
  aggregate(utils, evt, core, node, websocket_server, auth, codec_binary, js).
  settings(Build.settings("reactiveservices", publishToSonatype = false))

lazy val utils = Project(
  id = "core-utils",
  base = file("core-utils")
)


lazy val evt = Project(
  id = "core-evt",
  base = file("core-evt"),
  dependencies = Seq(
    utils
  )
)


lazy val core = Project(
  id = "core-services",
  base = file("core-services"),
  dependencies = Seq(
    evt
  )
)


lazy val node = Project(
  id = "core-node",
  base = file("core-node"),
  dependencies = Seq(
    evt ,
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
    evt ,
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



lazy val reactiveFxWeb = Project(id = "reactivefx-web", base = file("tmp/reactivefx/web"), dependencies = Seq(core, js)).enablePlugins(PlayScala, SbtWeb)
lazy val reactiveFxLegacyServiceAPI = Project(id = "reactivefx-legacyservice-api", base = file("tmp/reactivefx/legacyservice-api"))
lazy val reactiveFxLegacyService = Project(id = "reactivefx-legacyservice", base = file("tmp/reactivefx/legacyservice")).dependsOn(reactiveFxLegacyServiceAPI, core, node).enablePlugins(JavaAppPackaging)
lazy val reactiveFxPricesource = Project(id = "reactivefx-priceservice", base = file("tmp/reactivefx/priceservice")).dependsOn(reactiveFxLegacyServiceAPI, core, node).enablePlugins(JavaAppPackaging)
lazy val reactiveFxWebsocket = Project(id = "reactivefx-websocket", base = file("tmp/reactivefx/websocket")).dependsOn(websocket_server, auth, core, node).enablePlugins(JavaAppPackaging)



