ProjectBuild.coreSettings("examples-counter-node-websocket")

libraryDependencies ++= Dependencies.core

mainClass in Compile := Some("rs.node.Launcher")
