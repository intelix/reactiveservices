Build.settings("reactivefx-legacyservice")

val akkaVersion = "2.3.13"
val akkaStreamsVersion = "2.0.1"
val scalaMetricsVersion = "3.5.2_a2.3"
val scalaLoggingVersion = "3.1.0"
val logbackVersion = "1.1.2"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion
libraryDependencies += "ch.qos.logback" % "logback-classic" % logbackVersion
libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % akkaVersion


libraryDependencies += "com.typesafe.akka" %% "akka-stream-experimental" % akkaStreamsVersion
libraryDependencies += "nl.grons" %% "metrics-scala" % scalaMetricsVersion

mainClass in Compile := Some("backend.BackendBootstrap")