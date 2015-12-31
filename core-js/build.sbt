ProjectBuild.serviceSettings("rs-core-js") ++: ProjectBuild.sonatypeSettings

libraryDependencies ++= Dependencies.web

RjsKeys.mainModule := "rs-config"