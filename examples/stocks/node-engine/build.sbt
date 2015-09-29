ProjectBuild.coreSettings("examples-stocks-node-engine")

libraryDependencies ++= Dependencies.core

mainClass in Compile := Some("rs.node.Launcher")