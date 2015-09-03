ProjectBuild.coreSettings("core-sysevents")

libraryDependencies ++= Dependencies.essentials



publishMavenStyle := true
publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
publishArtifact in Test := false
pomIncludeRepository := { _ => false }

pomExtra := (
  <url>http://reactiveservices.org</url>
    <licenses>
      <license>
        <name>Apache</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:intelix/reactiveservices.git</url>
      <connection>scm:git:git@github.com:intelix/reactiveservices.git</connection>
    </scm>
    <developers>
      <developer>
        <id>mglukh</id>
        <name>Max Glukhovtsev</name>
        <url>http://github.com/intelix</url>
      </developer>
    </developers>)