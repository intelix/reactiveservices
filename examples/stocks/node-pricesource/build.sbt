ProjectBuild.coreSettings("examples-stocks-node-pricesource")

libraryDependencies ++= Dependencies.core

mainClass in Compile := Some("rs.node.Launcher")