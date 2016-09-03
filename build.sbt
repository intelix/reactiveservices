import Dependencies.Compile._
import Dependencies.Test._

lazy val root = project.in(file(".")).
  aggregate(essentials, config, ssl_config, evt, platform_core, platform_node, comp_websocket_ep, comp_auth, platform_codec_binary).
  settings(Build.settings("reactiveservices", publishToSonatype = false))





lazy val essentials = Project(id = "essentials", base = file("tools/essentials")).
  settings(Build.essentials("essentials")).
  settings(
    libraryDependencies ++= Seq(
      scalaReflect,
      scalaz,
      uuid,
      loggingScala,
      loggingLogback,
      jodaTime,
      jodaConvert,
      prettyTime,
      trove,
      guava,
      commonsCodec,
      playJson,
      playJsonZipper,
      jsr305              // needed for guava cache compilation
    )
  )

lazy val config = Project(id = "config", base = file("tools/config")).
  settings(Build.essentials("config")).
  settings(
    libraryDependencies ++= Seq(
      typesafeConfig,
      akkaActor,        // Props dependency
      ficus
    )
  ).dependsOn(essentials)



lazy val ssl_config = Project(id = "ssl-config", base = file("tools/ssl-config")).
  settings(Build.essentials("ssl-config")).
  dependsOn(config)

lazy val evt = Project(id = "evt", base = file("tools/evt")).
  settings(Build.essentials("evt")).
  settings(
    libraryDependencies ++= Seq(
      disruptor,
      playJson,
      playJsonZipper,
      scalaTest
    )
  ).dependsOn(config)





lazy val platform_core = Project(id = "core", base = file("platform/core")).
  settings(Build.essentials("core")).
  settings(
    libraryDependencies ++= Seq(
      netty,
      twitterChill,
      twitterChillBij,
      akkaActor,
      akkaAgent,
      akkaKernel,
      akkaCluster,
      akkaContrib,
      akkaRemote,
      akkaSlf4j,
      akkaStreams,
      akkaHttpCore,
      akkaHttp,
      kryoser,
      googleProtobuf,
      akkaTestKit,
      metricsScala
    )
  ).dependsOn(essentials, config, evt)


lazy val platform_node = Project(id = "node", base = file("platform/node")).
  settings(Build.essentials("node")).
  dependsOn(platform_core, ssl_config)


lazy val platform_codec_binary = Project(id = "codec-binary", base = file("platform/codec-binary")).
  settings(Build.essentials("codec-binary")).
  dependsOn(platform_core)



lazy val comp_auth = Project(id = "auth", base = file("components/auth")).
  settings(Build.essentials("auth")).
  dependsOn(platform_core, platform_node)


lazy val comp_websocket_ep = Project(id = "websocket-endpoint", base = file("components/websocket-endpoint")).
  settings(Build.essentials("websocket-endpoint")).
  settings(
    libraryDependencies ++= Seq(
      sprayWebsocket % "test"
    )
  ).dependsOn(platform_core, platform_node, platform_codec_binary, comp_auth)





//lazy val utils = Project(
//  id = "core-utils",
//  base = file("core-utils"),
//  dependencies = Seq(
//    essentials, config
//  )
//)


//lazy val evt = Project(
//  id = "core-evt",
//  base = file("core-evt"),
//  dependencies = Seq(
//    utils
//  )
//)


//lazy val core = Project(
//  id = "core-services",
//  base = file("core-services"),
//  dependencies = Seq(
//    evt,
//    utils,
//    config
//  )
//)


//lazy val node = Project(
//  id = "core-node",
//  base = file("core-node"),
//  dependencies = Seq(
//    evt,
//    platform_core,
//    ssl_config
//  )
//)

//lazy val codec_binary = Project(
//  id = "core-codec-binary",
//  base = file("core-codec-binary"),
//  dependencies = Seq(
//    platform_core
//  )
//)
//lazy val websocket_server = Project(
//  id = "websocket-server",
//  base = file("websocket-server"),
//  dependencies = Seq(
//    platform_core,
//    platform_node,
//    comp_auth,
//    platform_codec_binary
//  )
//)
//
//
//
//lazy val auth = Project(
//  id = "auth",
//  base = file("auth"),
//  dependencies = Seq(
//    evt,
//    platform_core,
//    platform_node
//  )
//)



//lazy val js = Project(
//  id = "core-js",
//  base = file("core-js"),
//  dependencies = Seq(
//    platform_core,
//    platform_node
//  )
//).enablePlugins(SbtWeb)



lazy val samples = Project(id = "samples", base = file("samples")).dependsOn(comp_websocket_ep, comp_auth, platform_core, platform_node).enablePlugins(DockerPlugin, JavaAppPackaging)


//lazy val reactiveFxWeb = Project(id = "reactivefx-web", base = file("tmp/reactivefx/web"), dependencies = Seq(core, js)).enablePlugins(PlayScala, SbtWeb)
//lazy val reactiveFxLegacyServiceAPI = Project(id = "reactivefx-legacyservice-api", base = file("tmp/reactivefx/legacyservice-api"))
//lazy val reactiveFxLegacyService = Project(id = "reactivefx-legacyservice", base = file("tmp/reactivefx/legacyservice")).dependsOn(reactiveFxLegacyServiceAPI, core, node).enablePlugins(JavaAppPackaging)
//lazy val reactiveFxPricesource = Project(id = "reactivefx-priceservice", base = file("tmp/reactivefx/priceservice")).dependsOn(reactiveFxLegacyServiceAPI, core, node).enablePlugins(JavaAppPackaging)
//lazy val reactiveFxWebsocket = Project(id = "reactivefx-websocket", base = file("tmp/reactivefx/websocket")).dependsOn(websocket_server, auth, core, node).enablePlugins(JavaAppPackaging)



