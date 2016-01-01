import com.typesafe.sbt.SbtLicenseReport.autoImportImpl._
import com.typesafe.sbt.digest.Import.digest
import com.typesafe.sbt.gzip.Import.gzip
import com.typesafe.sbt.less.Import.LessKeys
import com.typesafe.sbt.license.{DepModuleInfo, LicenseInfo}
import com.typesafe.sbt.rjs.Import.{RjsKeys, rjs}
import com.typesafe.sbt.web.Import.{WebJs, WebKeys}
import com.typesafe.sbt.web.SbtWeb.autoImport.{Assets, pipelineStages}
import sbt.Keys._
import sbt._
import WebJs._
import RjsKeys._

object ProjectBuild {
  def appName = "reactiveservices"

  lazy val baseSettings = Defaults.coreDefaultSettings

  val (mavenLocalResolver, mavenLocalResolverSettings) =
    System.getProperty("rms.build.M2Dir") match {
      case null => (Resolver.mavenLocal, Seq.empty)
      case path =>
        // Maven resolver settings
        val resolver = Resolver.file("user-publish-m2-local", new File(path))
        (resolver, Seq(
          otherResolvers := resolver :: publishTo.value.toList,
          publishM2Configuration := Classpaths.publishConfig(packagedArtifacts.value, None, resolverName = resolver.name, checksums = checksums.in(publishM2).value, logging = ivyLoggingLevel.value)
        ))
    }

  lazy val resolverSettings = {
    if (System.getProperty("rms.build.useLocalMavenResolver", "false").toBoolean)
      Seq(resolvers += mavenLocalResolver)
    else Seq.empty
  } ++ Seq(
    pomIncludeRepository := (_ => false)
  ) ++ Seq(
    resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
    resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/",
    resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
    resolvers += "mandubian maven bintray" at "http://dl.bintray.com/mandubian/maven",
    resolvers += "Spray" at "http://repo.spray.io",
    resolvers += "patriknw at bintray" at "http://dl.bintray.com/patriknw/maven"
  )

  lazy val compilerSettings = Seq(
    scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.8", "-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls"),
    javacOptions in compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation"),
    javacOptions in doc ++= Seq("-encoding", "UTF-8", "-source", "1.8"),
    incOptions := incOptions.value.withNameHashing(nameHashing = true),
    evictionWarningOptions in update := EvictionWarningOptions.default.withWarnTransitiveEvictions(false).withWarnDirectEvictions(false).withWarnScalaVersionEviction(false)

  )

  lazy val sharedProjectSettings = Seq(
    licenses := Seq(("Apache License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
    homepage := Some(url("http://reactiveservices.org/"))
  )

  lazy val testSettings = Seq(
    testOptions in Test += Tests.Argument("-oDF"),
    testListeners in(Test, test) := Seq(TestLogger(streams.value.log, { _ => streams.value.log }, logBuffered.value)),
    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
    parallelExecution in Test := false
  )

  lazy val concurrencySettings = Seq(
    concurrentRestrictions in Global := Seq(
      Tags.limit(Tags.Test, 1),
      Tags.limitAll(1)
    )
  )

  lazy val defaultSettings = baseSettings ++ resolverSettings ++ mavenLocalResolverSettings ++ compilerSettings ++ sharedProjectSettings ++ testSettings ++ concurrencySettings

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


  // Common settings for every project
  def settings(theName: String) = defaultSettings ++: Seq(
    name := theName,
    organization := "au.com.intelix",
    scalaVersion := "2.11.7",
    version := "0.1.1",
    doc in Compile <<= target.map(_ / "none")
  )

  // Settings for the app, i.e. the root project
  def coreSettings(appName: String) = settings(appName) ++: Seq(
    parallelExecution in Global := false
  )

  def webModuleSettings(module: String) = settings(module) ++: Seq(
    javaOptions in Test += s"-Dconfig.resource=application.conf",
    resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"
  )

  // Settings for every service, i.e. for admin and web subprojects
  def serviceSettings(module: String) = webModuleSettings(module) ++: Seq(
    includeFilter in(Assets, LessKeys.less) := "*.less",
    excludeFilter in(Assets, LessKeys.less) := "_*.less",

    pipelineStages := Seq(rjs, digest, gzip)
    //    RjsKeys.mainModule := s"main-$module"
  )


  lazy val sonatypeSettings = Seq(
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