import sbt._
import Keys._

import com.typesafe.sbt.SbtSite.site
import sbtrelease.ReleasePlugin._

import scala.util.Properties.envOrNone

object BuildSettings {
  import Helpers._

  final val buildOrganization = "org.log4s"
  final val baseVersion       = "1.0.2"
  final val buildScalaVersion = "2.10.4"
  final val buildJavaVersion  = "1.7"
  final val optimize          = true

  /** Whether to suffix the base version with the current build number. */
  val continuousBuild         = false
  val alwaysSnapshot          = true

  val buildScalaVersions = Seq("2.10.4", "2.11.2")

  val buildNumberOpt = opts("TRAVIS_BUILD_NUMBER", "BUILD_NUMBER")
  val isJenkins      = buildNumberOpt.isDefined

  val buildVersion = buildNumberOpt match {
    case _ if alwaysSnapshot                => s"$baseVersion-SNAPSHOT"
    case Some("") | Some("SNAPSHOT") | None => s"$baseVersion-SNAPSHOT"
    case Some(build) if continuousBuild     => s"$baseVersion.$build"
    case Some(_)                            => baseVersion
  }

  lazy val isSnapshot = buildVersion endsWith "-SNAPSHOT"

  lazy val buildScalacOptions = Seq (
    "-deprecation",
    "-unchecked",
    "-feature",
    "-target:jvm-" + buildJavaVersion
  ) ++ (
    if (optimize) Seq("-optimize") else Seq.empty
  )

  lazy val buildJavacOptions = Seq(
    "-target", buildJavaVersion,
    "-source", buildJavaVersion
  )

  lazy val siteSettings = site.settings ++ site.includeScaladoc()

  lazy val buildSettings = Defaults.defaultSettings ++
                           siteSettings ++
                           Seq (
    organization := buildOrganization,
    version      := buildVersion,
    licenses     := Seq("Apache License, Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    homepage     := Some(url("http://log4s.org")),
    description  := "High-performance SLF4J wrapper that provides convenient Scala bindings using macros.",
    startYear    := Some(2013),
    scmInfo      := Some(ScmInfo(url("http://github.com/Log4s/log4s"), "scm:git:git@github.com:Log4s/log4.git")),

    scalaVersion       := buildScalaVersion,
    crossScalaVersions := buildScalaVersions,
    autoAPIMappings    := true,

    scalacOptions ++= buildScalacOptions,
    javacOptions  ++= buildJavacOptions
  )
}

object Helpers {
  def getProp(name: String): Option[String] = sys.props.get(name) orElse sys.env.get(name)
  def parseBool(str: String): Boolean = Set("yes", "y", "true", "t", "1") contains str.trim.toLowerCase
  def boolFlag(name: String): Option[Boolean] = getProp(name) map { parseBool _ }
  def boolFlag(name: String, default: Boolean): Boolean = boolFlag(name) getOrElse default
  def opts(names: String*): Option[String] = names.view.map(getProp _).foldLeft(None: Option[String]) { _ orElse _ }
}

object Resolvers {
  val sarahSnaps    = "Sarah Snaps" at "https://repository-gerweck.forge.cloudbees.com/snapshot/"
  val sonaSnaps     = "Sonatype Snaps" at "https://oss.sonatype.org/content/repositories/snapshots"
  val sonaStage     = "Sonatype Staging" at "https://oss.sonatype.org/service/local/staging/deploy/maven2"
}

object PublishSettings {
  import BuildSettings._
  import Resolvers._
  import Helpers._

  val publishRepo = {
    if (isSnapshot) Some(sonaSnaps)
    else            Some(sonaStage)
  }

  val sonaCreds = (
    for {
      user <- getProp("SONATYPE_USER")
      pass <- getProp("SONATYPE_PASS")
    } yield {
      credentials +=
          Credentials("Sonatype Nexus Repository Manager",
                      "oss.sonatype.org",
                      user, pass)
    }
  ).toSeq

  val publishSettings = sonaCreds ++ Seq (
    publishMavenStyle       := true,
    pomIncludeRepository    := { _ => false },
    publishArtifact in Test := false,

    publishTo               := publishRepo,
    pomExtra                := (
      <developers>
        <developer>
          <id>sarah</id>
          <name>Sarah Gerweck</name>
          <url>https://github.com/sarahgerweck</url>
        </developer>
      </developers>
    )
  )
}

object Release {
  val settings = releaseSettings
}

object Eclipse {
  import com.typesafe.sbteclipse.plugin.EclipsePlugin._

  val settings = Seq (
    EclipseKeys.createSrc            := EclipseCreateSrc.Default + EclipseCreateSrc.Resource,
    EclipseKeys.projectFlavor        := EclipseProjectFlavor.Scala,
    EclipseKeys.executionEnvironment := Some(EclipseExecutionEnvironment.JavaSE17),
    EclipseKeys.withSource           := true
  )
}

object Dependencies {
  final val slf4jVersion = "1.7.7"

  val slf4j        = "org.slf4j"      % "slf4j-api"     % slf4jVersion
  //val scalaReflect = "org.scala-lang" % "scala-reflect" % scalaVersion.value
}

object Log4sBuild extends Build {
  build =>

  import BuildSettings._
  import Resolvers._
  import Dependencies._
  import PublishSettings._

  lazy val baseSettings = buildSettings ++ Eclipse.settings ++ publishSettings ++ Release.settings

  lazy val log4sDeps = Seq (
    slf4j
  )

  lazy val root = Project (
    id = "Log4s",
    base = file("."),
    settings = baseSettings ++ Seq (
      name := "Log4s",
      libraryDependencies ++= log4sDeps ++ Seq (
        "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided"
      )
    )
  )
}
