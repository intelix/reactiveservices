ProjectBuild.coreSettings("examples-stocks-node-websocket")

libraryDependencies ++= Dependencies.core

mainClass in Compile := Some("rs.node.Launcher")
