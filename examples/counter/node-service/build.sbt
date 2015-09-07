ProjectBuild.coreSettings("examples-counter-node-service")

libraryDependencies ++= Dependencies.core

mainClass in Compile := Some("rs.node.Launcher")