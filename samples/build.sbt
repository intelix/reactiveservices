Build.settings("samples")


mainClass in Compile := Some("au.com.intelix.rs.node.Launcher")

maintainer in Docker := "Max Glukhovtsev <max@intelix.com.au>"
packageSummary in Docker := "Sample - TODO update"

dockerExposedPorts := Seq(2801, 3801, 2803, 3803)
dockerExposedVolumes := Seq("/var/log", "/ssl")
dockerRepository := Some("mglukh")
dockerUpdateLatest := true
