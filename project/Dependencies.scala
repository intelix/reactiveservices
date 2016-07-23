import sbt._

object Dependencies {

  object Versions {


    val scalaVersion = "2.11.8"
    val scalaTestVersion = "2.2.6"
    val playJsonVersion = "2.5.4"
    val playJsonZipperVersion = "1.2"
    val guavaVersion = "19.0"
    val googleProtobufVersion = "2.6.1"
    val akkaVersion = "2.4.8"
//    val akkaStreamVersion = "2.0.3"
    val akkaHTTPVersion = "2.4.8"
    val scalaLoggingVersion = "3.4.0"
    val logbackVersion = "1.1.7"
    val jodaTimeVersion = "2.9.4"
    val jodaConvertVersion = "1.8.1"
    val prettytimeVersion = "4.0.1.Final"
    val ficusVersion = "1.1.2"
    val scalazVersion = "7.1.0"
    val kryoserVersion = "0.4.1"
    val nettyVersion = "3.10.3.Final"
    val troveVersion = "3.0.3"
    val metricsScalaVersion = "3.1.2"
    val uuidVersion = "3.2"
    val commonsCodecVersion = "1.10"

    val sprayWebsocketVersion = "0.1.4"

    val disruptorVersion = "3.3.5"

    val webjarsJqueryVersion = "2.1.4"
    val webjarsPlayVersion = "2.3.0-2"
    val webjarsBootstrapVersion = "3.3.6"
    val webjarsReqjsVersion = "2.1.22"
    val webjarsReqjsTxtVersion = "2.0.14-1"
    val webjarsReactJsVersion = "0.14.3"
    val webjarsJsSignalsVersion = "1.0.0"
    val webjarsUUIDVersion = "1.4.1"
    val webjarsLoDashVersion = "3.10.1"
    val webjarsClassnamesVersion = "2.2.0"

  }


  object Compile {
    
    import Versions._

    val scalaReflect      = "org.scala-lang"              %  "scala-reflect"                 % scalaVersion


    val playJson          = "com.typesafe.play"           %%  "play-json"                     % playJsonVersion
    val playJsonZipper    = "com.mandubian"               %%  "play-json-zipper"              % playJsonZipperVersion

    val akkaActor         = "com.typesafe.akka"           %% "akka-actor"                     % akkaVersion
    val akkaKernel        = "com.typesafe.akka"           %% "akka-kernel"                    % akkaVersion
    val akkaAgent         = "com.typesafe.akka"           %% "akka-agent"                     % akkaVersion
    val akkaSlf4j         = "com.typesafe.akka"           %% "akka-slf4j"                     % akkaVersion
    val akkaRemote        = "com.typesafe.akka"           %% "akka-remote"                    % akkaVersion
    val akkaCluster       = "com.typesafe.akka"           %% "akka-cluster"                   % akkaVersion
    val akkaPersistence   = "com.typesafe.akka"           %% "akka-persistence-experimental"  % akkaVersion
    val akkaStreams       = "com.typesafe.akka"           %% "akka-stream"                    % akkaVersion
    val akkaHttpCore      = "com.typesafe.akka"           %% "akka-http-core"                 % akkaVersion
    val akkaHttp          = "com.typesafe.akka"           %% "akka-http-experimental"         % akkaVersion
    val akkaContrib       = "com.typesafe.akka"           %% "akka-contrib"                   % akkaVersion

    val googleProtobuf    = "com.google.protobuf"         %  "protobuf-java"                  % googleProtobufVersion
    val loggingScala      = "com.typesafe.scala-logging"  %% "scala-logging"                  % scalaLoggingVersion
    val loggingLogback    = "ch.qos.logback"              %  "logback-classic"                % logbackVersion
    val jodaTime          = "joda-time"                   %  "joda-time"                      % jodaTimeVersion
    val jodaConvert       = "org.joda"                    %  "joda-convert"                   % jodaConvertVersion
    val prettyTime        = "org.ocpsoft.prettytime"      %  "prettytime"                     % prettytimeVersion
    val ficus             = "net.ceedubs"                 %% "ficus"                          % ficusVersion
    val scalaz            = "org.scalaz"                  %% "scalaz-core"                    % scalazVersion
    val metricsScala      = "io.dropwizard.metrics"       %   "metrics-core"                  % metricsScalaVersion
    val uuid              = "com.eaio.uuid"               %  "uuid"                           % uuidVersion
    val commonsCodec      = "commons-codec"               %  "commons-codec"                  % commonsCodecVersion
    val kryoser           = "com.github.romix.akka"       %% "akka-kryo-serialization"        % kryoserVersion
    val netty             = "io.netty"                    %   "netty"                         % nettyVersion force()
    val trove             = "net.sf.trove4j"              %   "trove4j"                       % troveVersion
    val guava             = "com.google.guava"            %   "guava"                         % guavaVersion force()
    val disruptor         = "com.lmax"                    %   "disruptor"                     % disruptorVersion
    val sprayWebsocket    = "com.wandoulabs.akka"         %%   "spray-websocket"              % sprayWebsocketVersion
    val webjarsPlay       = "org.webjars"                 %% "webjars-play"                   % webjarsPlayVersion
    val webjarsJquery     = "org.webjars"                 %  "jquery"                         % webjarsJqueryVersion
    val webjarsBootstrap  = "org.webjars"                 %  "bootstrap"                      % webjarsBootstrapVersion
    val webjarsReqjs      = "org.webjars"                 %  "requirejs"                      % webjarsReqjsVersion
    val webjarsReqjsTxt   = "org.webjars"                 %  "requirejs-text"                 % webjarsReqjsTxtVersion
    val webjarsReactJs    = "org.webjars"                 %  "react"                          % webjarsReactJsVersion
    val webjarsJsSignals  = "org.webjars"                 %  "js-signals"                     % webjarsJsSignalsVersion
    val webjarsUUID       = "org.webjars"                 %  "uuid"                           % webjarsUUIDVersion
    val webjarsLoDash     = "org.webjars"                 %  "lodash"                         % webjarsLoDashVersion
    val webjarsClassnames = "org.webjars.bower"           %  "classnames"                     % webjarsClassnamesVersion

  }
  
  object Test {
    import Versions._

    val scalaTest         = "org.scalatest"               %% "scalatest"                      % scalaTestVersion
    val akkaMultiNode     = "com.typesafe.akka"           %% "akka-multi-node-testkit"        % akkaVersion           % "test"
    val akkaTestKit       = "com.typesafe.akka"           %% "akka-testkit"                   % akkaVersion           % "test"
    val akkaMNTestkit     = "com.typesafe.akka"           %% "akka-multi-node-testkit"        % akkaVersion           % "test"

  }

  import Compile._

  lazy val core = Seq(
    scalaReflect,
    ficus,
    scalaz,
    uuid,
    loggingScala,
    loggingLogback,
    jodaTime,
    jodaConvert,
    prettyTime,
    playJson,
//    playJsonZipper,
    Test.scalaTest,
    trove,
    guava,
    netty,
    disruptor,
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
    Test.akkaTestKit,
    commonsCodec,
    metricsScala,
    sprayWebsocket,
    "com.google.code.findbugs" % "jsr305" % "2.0.3"  // !>>> DO PROPERLY!
  )

  lazy val web = core ++ Seq(
    Test.akkaTestKit,
    webjarsBootstrap,
    webjarsJquery,
    webjarsPlay,
    webjarsReactJs,
    webjarsReqjs,
    webjarsReqjsTxt,
    webjarsJsSignals,
    webjarsUUID,
    webjarsLoDash,
    webjarsClassnames
  )

}

