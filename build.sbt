import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

/** okay-desk — chat with your data in the browser, offline: a small model in the page writes the program, exact
 * handlers run it (specs/data.md). The model never computes a number. */
ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror")
ThisBuild / resolvers ++= Seq(
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-datalog" at "https://okay-effects.github.io/okay-datalog/maven",
  "okay-ui" at "https://okay-effects.github.io/okay-ui/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven")

/** the tables, the query programs (data), the exact handlers (relational and Datalog), the model's prompt and
 * its answer's parsing — plain Scala: the JVM for the laws, the browser for the page */
lazy val core = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("core"))
  .settings(name := "okay-desk",
    libraryDependencies ++= Seq(
      "io.github.okay-effects" %%% "okay-datalog" % "0.1.0",
      "io.github.okay-effects" %%% "okay-inlay" % "0.1.0",
      "io.github.okay-effects" %%% "okay-llm-qwen" % "0.1.0",
      "io.github.okay-effects" %%% "okay-scala" % "0.1.1",
      "io.github.okay-effects" %%% "okay-effects-json-value" % "0.1.1",
      "org.scalameta" %%% "munit" % "1.2.3" % Test))

/** THE DESK AS AN OKAY-UI PROGRAM (specs/data.md §16): the screen, the messages, the effects `Model` and okay-http's
 * `Fetch` — plain data; played by a test script on the JVM, drawn by okay-ui's DOM face in the page */
lazy val ui = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("ui")).dependsOn(core)
  .settings(name := "okay-desk-ui", publish / skip := true,
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-ui" % "0.1.0", "org.scalameta" %%% "munit" % "1.2.3" % Test))

/** the page: files opened in the browser, the model (WebLLM, WebGPU) in the page, the chat */
lazy val web = (project in file("web")).enablePlugins(ScalaJSPlugin).dependsOn(ui.js)
  .settings(name := "okay-desk-web", publish / skip := true, scalaJSUseMainModuleInitializer := true,
    // the readers' word boundaries with Cyrillic ((?U)) need ES2018's regular expressions
    scalaJSLinkerConfig ~= { _.withESFeatures(_.withESVersion(org.scalajs.linker.interface.ESVersion.ES2018)) },
    scalaJSLinkerConfig ~= (_.withModuleKind(org.scalajs.linker.interface.ModuleKind.NoModule)),
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-llm-qwen-browser" % "0.1.0"))

lazy val root = (project in file(".")).aggregate(core.jvm, core.js, ui.jvm, ui.js, web).settings(publish / skip := true)

/** THE BENCH (specs/data.md §3): the questions, in English and Russian, over sample tables with gold programs; each
 * model (mlx-scala, Apple's GPU) asked with the page's prompt; its program run by the exact handlers and compared to
 * the gold result. `sbt "bench/run"`; DESK_MODELS a comma list of repositories, smallest first */
val mlxNativeLib = sys.env.get("MLX_SCALA_LIB").map(file).getOrElse(file("../mlx-scala/native-lib/out/lib")).getCanonicalFile
lazy val bench = (project in file("bench")).dependsOn(core.jvm)
  .settings(name := "okay-desk-bench", publish / skip := true,
    resolvers += "mlx-scala" at "https://sergey-scherbina.github.io/mlx-scala/maven",
    libraryDependencies ++= Seq("io.github.sergey-scherbina" %% "mlx-scala" % "0.1.0",
      "io.github.sergey-scherbina" %% "mlx-scala-llm" % "0.1.0", "io.github.okay-effects" %% "okay-llm-qwen-mlx" % "0.1.0",
      "org.scalameta" %% "munit" % "1.2.3" % Test),
    fork := true, run / fork := true, Test / fork := true,
    javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED", s"-Dokm.lib=$mlxNativeLib/libokm.dylib", "-Xss16m"))
