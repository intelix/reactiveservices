ProjectBuild.coreSettings("examples-counter-node-service")

libraryDependencies ++= Dependencies.eventStreamsCore

mainClass in Compile := Some("rs.node.Launcher")