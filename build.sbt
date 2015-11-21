import sbt.Project.projectToRef

lazy val jsProjects = Seq(client)

lazy val commonSettings = Seq(
  version := "0.8",
  organization := "in.ernet.iisc.math",
  scalaVersion := "2.11.7",
  resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.4",
  "org.spire-math" %% "spire" % "0.9.1",
  "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test"
  ),
  scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")
  )

val akkaStreamV = "2.0-M1"

lazy val jvmSettings = Seq(
  libraryDependencies ++= Seq(
    "com.lihaoyi" % "ammonite-repl" % "0.4.8" % "test" cross CrossVersion.full,
    "com.github.nscala-time" %% "nscala-time" % "2.0.0",
    "org.reactivemongo" %% "reactivemongo" % "0.11.6",
    "com.typesafe.akka" %% "akka-actor" % "2.3.11",
    "com.typesafe.akka" %% "akka-slf4j" % "2.3.11",
    "ch.qos.logback" % "logback-classic" % "1.0.9",
    "com.typesafe" % "config" % "1.3.0",
    "org.mongodb" %% "casbah" % "3.0.0",
    "org.mongodb.scala" %% "mongo-scala-driver" % "1.0.0",
    "com.typesafe.akka" %% "akka-stream-experimental"             % akkaStreamV,
    "com.typesafe.akka" %% "akka-http-core-experimental"          % akkaStreamV,
    "com.typesafe.akka" %% "akka-http-experimental"               % akkaStreamV,
    "com.typesafe.akka" %% "akka-http-spray-json-experimental"    % akkaStreamV,
    "com.lihaoyi" %% "upickle" % "0.3.4",
    "com.lihaoyi" %% "ammonite-ops" % "0.4.8",
    "org.scala-lang.modules" %% "scala-pickling" % "0.10.1",
"com.lihaoyi" %% "pprint" % "0.3.6")
  )

lazy val logback = "ch.qos.logback" % "logback-classic" % "1.0.9"

lazy val serverSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    ws,
    "org.reactivemongo" %% "play2-reactivemongo" % "0.11.2.play24",
    "com.vmunier" %% "play-scalajs-scripts" % "0.3.0",
    "org.webjars" % "jquery" % "1.11.1"
  ),
  scalaJSProjects := jsProjects,
  pipelineStages := Seq(scalaJSProd),
  initialCommands in console := """import provingground._ ; import HoTT._; /*import pprint.Config.Colors._; import pprint.pprintln*/"""
  )

lazy val nlpSettings = Seq(
  libraryDependencies ++= Seq(
    "edu.stanford.nlp" % "stanford-corenlp" % "3.4",
    "edu.stanford.nlp" % "stanford-corenlp" % "3.4" classifier "models"
    )
  )

lazy val digressionSettings = Seq(
  name := "ProvingGround-Digressions",
  libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-actor" % "2.3.11"
  ),
  scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")
  )

lazy val acSettings = Seq(
  name := "AndrewsCurtis",
  libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-actor" % "2.3.11"
//    ,"com.lihaoyi" % "ammonite-repl" % "0.3.2" % "test" cross CrossVersion.full
    ),
  scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
  initialCommands in console := """import provingground.andrewscurtis._"""
//  ,initialCommands in (Test, console) :=
//    """ammonite.repl.Repl.run("import provingground.andrewscurtis._; import provingground._; import FreeGroups._; import Presentation._; import ACevolution._")"""
  )

lazy val nfSettings = Seq(
  name := "NormalForm",
  libraryDependencies ++= Seq("org.spire-math" %% "spire" % "0.9.1"),
  scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
  initialCommands in console := """import provingground.normalform._ ; import provingground.normalform.NormalForm._"""
  )

lazy val client = project.
  settings(name := "ProvingGround-JS",
  scalaVersion := "2.11.7",
  persistLauncher := true,
  persistLauncher in Test := false,
  sourceMapsDirectories += coreJS.base / "..",
  unmanagedSourceDirectories in Compile := Seq((scalaSource in Compile).value),
  libraryDependencies ++= Seq(
    "org.scala-js" %%% "scalajs-dom" % "0.8.0",
    "com.lihaoyi" %%% "scalatags" % "0.4.6",
    "com.lihaoyi" %%% "upickle" % "0.3.4"
    )
  ).
  enablePlugins(ScalaJSPlugin, ScalaJSPlay).
  dependsOn(coreJS)



lazy val core = (crossProject.crossType(CrossType.Pure) in  file("core")).
  settings(commonSettings : _*).
  settings(name := "ProvingGround-Core").
  settings(libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % "0.3.4"
    )).
  jsConfigure(_ enablePlugins ScalaJSPlay).
  jsSettings(sourceMapsBase := baseDirectory.value / "..")

lazy val coreJVM = core.jvm
lazy val coreJS = core.js

lazy val functionfinder = project.
  settings(commonSettings: _*).
  settings(name := "ProvingGround-FunctionFinder").
  dependsOn(coreJVM)

lazy val mizar = project.
  settings(commonSettings: _*).
  settings(jvmSettings : _ *).
  settings(name := "Mizar-Parser",
  libraryDependencies += "com.lihaoyi" %% "fastparse" % "0.2.1")

lazy val mantle = (project in file("mantle")).
        settings(name := "ProvingGround-mantle").
        settings(commonSettings : _*).
        settings(jvmSettings : _*).
        settings(serverSettings : _*).
        settings(initialCommands in (Test, console) := """ammonite.repl.Repl.run(
          "import provingground._; import HoTT._; import ammonite.ops._")""").
        dependsOn(coreJVM).dependsOn(functionfinder)

lazy val nlp = (project in file("nlp")).
        settings(name := "ProvingGround-NLP").
        settings(commonSettings : _*).
        settings(nlpSettings : _*).
        settings(jvmSettings : _*).
        settings(serverSettings : _*).
        dependsOn(coreJVM).dependsOn(functionfinder)

lazy val playServer = (project in file("play-server")).enablePlugins(PlayScala).
        settings(name := "ProvingGround-Play-Server").
        settings(commonSettings : _*).
        settings(jvmSettings : _*).
        settings(serverSettings : _*).
        settings(libraryDependencies += specs2 % Test,
        resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases",
        TwirlKeys.templateImports += "controllers._").
        aggregate(jsProjects.map(projectToRef): _*).
        dependsOn(coreJVM).
        dependsOn(functionfinder).
        dependsOn(andrewscurtis).
        dependsOn(mantle).
        dependsOn(nlp)

lazy val realfunctions = (project in file("realfunctions")).
        settings(commonSettings : _*).
        settings(jvmSettings : _ *).
        settings(libraryDependencies  ++= Seq(
          // other dependencies here
          //"org.scalanlp" %% "breeze" % "0.11.2",
          // native libraries are not included by default. add this if you want them (as of 0.7)
          // native libraries greatly improve performance, but increase jar sizes.
          //"org.scalanlp" %% "breeze-natives" % "0.11.2"
          ),
          resolvers ++= Seq(
            // other resolvers here
            // if you want to use snapshot builds (currently 0.12-SNAPSHOT), use this.
            "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
            "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/"
            ),
            name := "RealFunctions")



lazy val digressions = (project in file("digressions")).
  settings(commonSettings : _*).
  settings(digressionSettings : _*).
  dependsOn(coreJVM).dependsOn(playServer).dependsOn(functionfinder)

  EclipseKeys.skipParents in ThisBuild := false

// unmanagedBase in Compile <<= baseDirectory(_ / "scalalib")

lazy val andrewscurtis = (project in file("andrewscurtis")).
  settings(commonSettings : _*).
  settings(jvmSettings : _*).
  settings(acSettings : _*).
  dependsOn(coreJVM).dependsOn(mantle)

lazy val normalform = (project in file("normalform")).
  settings(commonSettings : _*).
  settings(nfSettings : _*)

fork in run := true
