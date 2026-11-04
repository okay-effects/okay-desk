import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

/** okay-data — chat with your data in the browser, offline: a small model in the page writes the program, exact
 * handlers run it (specs/data.md). The model never computes a number. */
ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror")
ThisBuild / resolvers ++= Seq(
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-datalog" at "https://okay-effects.github.io/okay-datalog/maven")

/** the tables, the query programs (data), the exact handlers (relational and Datalog), the model's prompt and
 * its answer's parsing — plain Scala: the JVM for the laws, the browser for the page */
lazy val core = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("core"))
  .settings(name := "okay-data",
    libraryDependencies ++= Seq(
      "io.github.okay-effects" %%% "okay-datalog" % "0.1.0",
      "io.github.okay-effects" %%% "okay-effects-json-value" % "0.1.1",
      "org.scalameta" %%% "munit" % "1.2.3" % Test))

/** the page: files opened in the browser, the model (WebLLM, WebGPU) in the page, the chat */
lazy val web = (project in file("web")).enablePlugins(ScalaJSPlugin).dependsOn(core.js)
  .settings(name := "okay-data-web", publish / skip := true, scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(org.scalajs.linker.interface.ModuleKind.NoModule)),
    libraryDependencies ++= Seq("com.raquo" %%% "laminar" % "17.2.1"))

lazy val root = (project in file(".")).aggregate(core.jvm, core.js, web).settings(publish / skip := true)
