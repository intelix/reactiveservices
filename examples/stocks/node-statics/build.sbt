
name := """node-statics"""

version := "0.1.0"

lazy val root = (project in file(".")).enablePlugins(PlayScala, SbtWeb)


scalaVersion := "2.11.7"

scalacOptions := Seq(
  "-deprecation",
  "-encoding", "utf8",
  "-feature",
  "-language:higherKinds",
  "-unchecked",
  "-Xlint",
  "-Xfatal-warnings",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen"
)

val rsVersion = "0.1.0"

libraryDependencies ++= Seq(
  "au.com.intelix"              %% "rs-core-node"                   % rsVersion,
  "au.com.intelix"              %% "rs-core-js"                     % rsVersion
)

includeFilter in(Assets, LessKeys.less) := "*.less"
excludeFilter in(Assets, LessKeys.less) := "_*.less"

pipelineStages := Seq(rjs, digest, gzip)

