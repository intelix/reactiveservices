
name := """node-websocket"""

version := "0.1.0"

lazy val root = (project in file(".")).enablePlugins(JavaServerAppPackaging)

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
  "au.com.intelix"              %% "rs-auth"                        % rsVersion,
  "au.com.intelix"              %% "rs-websocket-server"            % rsVersion
)


mainClass in Compile := Some("rs.node.Launcher")