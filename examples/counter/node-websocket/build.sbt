ProjectBuild.coreSettings("examples-counter-node-websocket")

libraryDependencies ++= Dependencies.eventStreamsCore

mainClass in Compile := Some("rs.node.Launcher")
