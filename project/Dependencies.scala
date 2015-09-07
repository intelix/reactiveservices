import sbt._

object Dependencies {

  object Versions {


    val scalaVersion = "2.11.6"
    val scalaTestVersion = "2.2.1"

    val playCacheVersion = "2.2.1"
    val playJsonVersion = "2.3.8"

    val playJsonZipperVersion = "1.2"

    val slickVersion = "2.1.0"
    val h2Version = "1.3.166"

    val groovyVersion = "2.3.8"

    val akkaVersion = "2.3.12"
    val akkaStreamVersion = "1.0"
    val akkaHTTPVersion = "1.0"
    val akkaDataReplVersion = "0.11"

    val scalaLoggingVersion = "3.1.0"
    val logbackVersion = "1.1.2"
    
    val jodaTimeVersion = "2.3"
    val jodaConvertVersion = "1.6"
    val prettytimeVersion = "3.2.5.Final"

    val ficusVersion = "1.1.1"
    
    val scalazVersion = "7.1.0"
    
    val elastic4sVersion = "1.4.12"
    
    val asyncHttpVersion = "1.0.0"

    val metricsScalaVersion = "3.1.0"

    val uuidVersion = "3.2"
    
    val janalyseJmxVersion = "0.7.1"
    
    val commonsCodecVersion = "1.9"

    val sprayWebsocketVersion = "0.1.4"

    val webjarsJqueryVersion = "2.1.3"
    val webjarsPlayVersion = "2.3.0-2"
    val webjarsBootswatchVersion = "3.3.1+2"
    val webjarsBootstrapVersion = "3.3.1"
    val webjarsReqjsVersion = "2.1.14-3"
    val webjarsReqjsTxtVersion = "2.0.10-1"
    val webjarsReactJsVersion = "0.12.1"
    val webjarsToastrVersion = "2.1.0"
    val webjarsCryptoJSVersion = "3.1.2"
    val webjarsJsSignalsVersion = "1.0.0"

    val kryoserVersion = "0.3.3"
    
    val nettyVersion = "3.9.3.Final"

    val troveVersion = "3.0.2"
    
  }


  object Compile {
    
    import Versions._


    val playCache       = "com.typesafe.play"           %%  "play-cache"                    % playCacheVersion
    val playJson        = "com.typesafe.play"           %%  "play-json"                     % playJsonVersion
    val playJsonZipper  = "com.mandubian"               %%  "play-json-zipper"              % playJsonZipperVersion

    val slick           = "com.typesafe.slick"          %%  "slick"                         % slickVersion
    val h2              = "com.h2database"              %   "h2"                            % h2Version

    val groovy          = "org.codehaus.groovy"         %   "groovy-all"                    % groovyVersion

    val akkaActor       = "com.typesafe.akka"           %% "akka-actor"                     % akkaVersion
    val akkaKernel      = "com.typesafe.akka"           %% "akka-kernel"                    % akkaVersion
    val akkaAgent       = "com.typesafe.akka"           %% "akka-agent"                     % akkaVersion
    val akkaSlf4j       = "com.typesafe.akka"           %% "akka-slf4j"                     % akkaVersion
    val akkaRemote      = "com.typesafe.akka"           %% "akka-remote"                    % akkaVersion
    val akkaCluster     = "com.typesafe.akka"           %% "akka-cluster"                   % akkaVersion
    val akkaPersistence = "com.typesafe.akka"           %% "akka-persistence-experimental"  % akkaVersion
    val akkaStreams     = "com.typesafe.akka"           %% "akka-stream-experimental"       % akkaStreamVersion
    val akkaHttpCore    = "com.typesafe.akka"           %% "akka-http-core-experimental"    % akkaHTTPVersion
    val akkaHttp        = "com.typesafe.akka"           %% "akka-http-experimental"         % akkaHTTPVersion
    val akkaDataRepl    = "com.github.patriknw"         %% "akka-data-replication"          % akkaDataReplVersion
    val akkaContrib     = "com.typesafe.akka"           %% "akka-contrib"                   % akkaVersion

    val loggingScala    = "com.typesafe.scala-logging"  %% "scala-logging"                  % scalaLoggingVersion
    val loggingLogback  = "ch.qos.logback"              %  "logback-classic"                % logbackVersion

    val jodaTime        = "joda-time"                   %  "joda-time"                      % jodaTimeVersion
    val jodaConvert     = "org.joda"                    %  "joda-convert"                   % jodaConvertVersion
    val prettyTime      = "org.ocpsoft.prettytime"      %  "prettytime"                     % prettytimeVersion

    val webjarsPlay     = "org.webjars"                 %% "webjars-play"                   % webjarsPlayVersion
    val webjarsJquery   = "org.webjars"                 %  "jquery"                         % webjarsJqueryVersion
    val webjarsBootswatch = "org.webjars"               %  "bootswatch-cosmo"               % webjarsBootswatchVersion
    val webjarsBootstrap= "org.webjars"                 %  "bootstrap"                      % webjarsBootstrapVersion
    val webjarsReqjs    = "org.webjars"                 %  "requirejs"                      % webjarsReqjsVersion
    val webjarsReqjsTxt = "org.webjars"                 %  "requirejs-text"                 % webjarsReqjsTxtVersion
    val webjarsToastr   = "org.webjars"                 %  "toastr"                         % webjarsToastrVersion
    val webjarsReactJs  = "org.webjars"                 %  "react"                          % webjarsReactJsVersion
    val webjarsCryptoJs = "org.webjars"                 %  "cryptojs"                       % webjarsCryptoJSVersion
    val webjarsJsSignals= "org.webjars"                 %  "js-signals"                     % webjarsJsSignalsVersion


    val ficus           = "net.ceedubs"                 %% "ficus"                          % ficusVersion
    val scalaz          = "org.scalaz"                  %% "scalaz-core"                    % scalazVersion
    val elastic4s       = "com.sksamuel.elastic4s"      %% "elastic4s"                      % elastic4sVersion
    val asyncHttpClient = "com.ning"                    %  "async-http-client"              % asyncHttpVersion
    val metricsScala    = "io.dropwizard.metrics"       %   "metrics-core"                  % metricsScalaVersion
    val uuid            = "com.eaio.uuid"               %  "uuid"                           % uuidVersion
    val janalyseJmx     = "fr.janalyse"                 %% "janalyse-jmx"                   % janalyseJmxVersion
    val commonsCodec    = "commons-codec"               %  "commons-codec"                  % commonsCodecVersion
    
    val kryoser         = "com.github.romix.akka"       %% "akka-kryo-serialization"        % kryoserVersion
    val netty           = "io.netty"                    %   "netty"                         % nettyVersion
    val sprayWebsocket  = "com.wandoulabs.akka"         %%   "spray-websocket"              % sprayWebsocketVersion

    val trove           = "net.sf.trove4j"              %   "trove4j"                       % troveVersion
  }
  
  object Test {
    import Versions._

    val scalaTest       = "org.scalatest"               %% "scalatest"                      % scalaTestVersion      % "test"
    val akkaMultiNode   = "com.typesafe.akka"           %% "akka-multi-node-testkit"        % akkaVersion           % "test"
    val akkaTestKit     = "com.typesafe.akka"           %% "akka-testkit"                   % akkaVersion           % "test"
    val akkaMNTestkit   = "com.typesafe.akka"           %% "akka-multi-node-testkit"        % akkaVersion           % "test"

  }

  import Compile._



  val essentials = Seq(
    ficus,
    scalaz,
    uuid,
    loggingScala,
    loggingLogback,
    jodaTime,
    jodaConvert,
    prettyTime,
    playJson,
    playJsonZipper,
    Test.scalaTest,
    trove
  )

  val commons = essentials ++: Seq(
    netty,
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
    akkaDataRepl,
    kryoser,
    asyncHttpClient,

    sprayWebsocket

  )

  val core = commons ++: Seq(
    Test.akkaTestKit,
    commonsCodec,
    metricsScala
  )


  val web = commons ++: Seq(
    Test.akkaTestKit,
    webjarsBootstrap,
    webjarsJquery,
    webjarsPlay,
    webjarsReactJs,
    webjarsReqjs,
    webjarsReqjsTxt,
    webjarsToastr,
    webjarsCryptoJs,
    webjarsJsSignals
  )

  val multinodeTests = commons ++: Seq(
    Test.akkaTestKit,
    Test.akkaMNTestkit
  )
  
}

