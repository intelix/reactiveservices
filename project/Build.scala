import com.typesafe.sbt.SbtLicenseReport.autoImportImpl._
import com.typesafe.sbt.digest.Import.digest
import com.typesafe.sbt.gzip.Import.gzip
import com.typesafe.sbt.less.Import.LessKeys
import com.typesafe.sbt.license.{DepModuleInfo, LicenseInfo}
import com.typesafe.sbt.pgp.PgpKeys.pgpSigningKey
import com.typesafe.sbt.rjs.Import.rjs
import com.typesafe.sbt.web.SbtWeb.autoImport.{Assets, pipelineStages}
import sbt.Keys._
import sbt._


private object Settings {

  lazy val baseSettings = Defaults.coreDefaultSettings

  lazy val artifactSettings = Seq(
    version := "0.1.3_14-SNAPSHOT",
    organization := "au.com.intelix",
    licenses := Seq(("Apache License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
    homepage := Some(url("http://reactiveservices.org/"))
  )

  lazy val resolverSettings = Seq(
    resolvers += "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
    resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
    resolvers += "mandubian maven bintray" at "http://dl.bintray.com/mandubian/maven",
    resolvers += "Spray" at "http://repo.spray.io",
    resolvers += "patriknw at bintray" at "http://dl.bintray.com/patriknw/maven"
  )

  lazy val compilerSettings = Seq(
    scalaVersion := "2.11.7",
    scalacOptions in Compile ++= Seq(
      "-encoding", "UTF-8",
      "-target:jvm-1.8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xlog-reflective-calls",
      "-Xlint",
      "-Xfatal-warnings",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen"),
    javacOptions in compile ++= Seq(
      "-encoding", "UTF-8",
      "-source", "1.8",
      "-target", "1.8",
      "-Xlint:unchecked",
      "-Xlint:deprecation"),
    incOptions := incOptions.value.withNameHashing(nameHashing = true),
    evictionWarningOptions in update := EvictionWarningOptions
      .default.withWarnTransitiveEvictions(false).withWarnDirectEvictions(false).withWarnScalaVersionEviction(false),
    doc in Compile <<= target.map(_ / "none")
  )

  lazy val testSettings = Seq(
    testOptions in Test += Tests.Argument("-oDF", "-Devt-config=evt-test"),
    testListeners in(Test, test) := Seq(TestLogger(streams.value.log, { _ => streams.value.log }, logBuffered.value)),
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")
  )

  lazy val concurrencySettings = Seq(
    concurrentRestrictions in Global := Seq(
      Tags.limit(Tags.Test, 1),
      Tags.limitAll(1)
    ),
    parallelExecution in Global := false
  )

}


object Build {

  import Settings._

  lazy val defaultSettings = baseSettings ++ artifactSettings ++ resolverSettings ++ compilerSettings ++ testSettings ++ concurrencySettings

  licenseOverrides := {
    case DepModuleInfo(_, "prettytime", _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("cglib", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("com.fasterxml.jackson.core", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("com.google.guava", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("com.h2database", _, _) => LicenseInfo(LicenseCategory.Mozilla, "MPL 2.0", "http://www.mozilla.org/MPL/2.0")
    case DepModuleInfo("com.typesafe.play", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("commons-codec", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("commons-collections", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("commons-io", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("commons-logging", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("fr.janalyse", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("io.dropwizard.metrics", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("log4j", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("oauth.signpost", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("org.antlr", _, _) => LicenseInfo(LicenseCategory.BSD, "BSD", "https://github.com/antlr/antlr4/blob/master/LICENSE.txt")
    case DepModuleInfo("org.apache.commons", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("org.easytesting", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("org.apache.httpcomponents", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("org.apache.lucene", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("org.eclipse.jetty", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("org.fusesource.hawtjni", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("org.fusesource.leveldbjni", _, _) => LicenseInfo(LicenseCategory.BSD, "New BSD", "http://opensource.org/licenses/BSD-3-Clause")
    case DepModuleInfo("org.hamcrest", _, _) => LicenseInfo(LicenseCategory.BSD, "BSD", "http://opensource.org/licenses/BSD-3-Clause")
    case DepModuleInfo("org.ow2.asm", _, _) => LicenseInfo(LicenseCategory.BSD, "BSD", "http://asm.ow2.org/asmdex-license.html")
    case DepModuleInfo("org.iq80.leveldb", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("org.json", _, _) => LicenseInfo(LicenseCategory.NoneSpecified, "???", "http://www.json.org/license.html")
    case DepModuleInfo("org.reactivestreams", _, _) => LicenseInfo(LicenseCategory.PublicDomain, "CC0", "http://creativecommons.org/publicdomain/zero/1.0/")
    case DepModuleInfo("org.seleniumhq.selenium", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
    case DepModuleInfo("org.slf4j", _, _) => LicenseInfo(LicenseCategory.MIT, "MIT", "http://www.slf4j.org/license.html")
    case DepModuleInfo("org.w3c.css", _, _) => LicenseInfo(LicenseCategory.GPL, "GPL", "http://www.w3.org/Consortium/Legal/copyright-software-19980720")
    case DepModuleInfo(_, "bootswatch-cerulean", _) => LicenseInfo(LicenseCategory.MIT, "MIT", "https://github.com/thomaspark/bootswatch/blob/gh-pages/LICENSE")
    case DepModuleInfo(_, "requirejs", _) => LicenseInfo(LicenseCategory.MIT, "MIT", "http://www.opensource.org/licenses/mit-license.php")
    case DepModuleInfo("xalan", _, _) => LicenseInfo(LicenseCategory.Apache, "Apache 2", "http://www.apache.org/licenses/LICENSE-2.0")
  }


  def settings(module: String, publishToSonatype: Boolean = true) = defaultSettings ++ Seq(
    name := module,
    libraryDependencies ++= Dependencies.core
  ) ++ (if (publishToSonatype) sonatypeSettings else Seq())

  def web = Seq(
    includeFilter in(Assets, LessKeys.less) := "*.less",
    excludeFilter in(Assets, LessKeys.less) := "_*.less",
    pipelineStages := Seq(rjs, digest, gzip),
    libraryDependencies ++= Dependencies.web
  )

  def disablePublishing = Seq(
    publish := {},
    publishLocal := {},
    publishArtifact := false,
    pgpSigningKey := None,
    publishMavenStyle := false
  )

  //noinspection ScalaUnnecessaryParentheses
  def sonatypeSettings = Seq(
    publishMavenStyle := true,
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    pomExtra := (
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
  )

}