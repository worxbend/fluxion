import mill._
import mill.scalalib._

val picocliVersion         = "4.7.6"
val tambuiVersion          = "0.3.0-SNAPSHOT"
val jacksonVersion         = "2.17.2"
val slf4jVersion           = "2.0.13"
val logbackVersion         = "1.5.6"
val junitVersion           = "5.11.0"
val junitPlatformVersion   = "1.11.0"
val junitInterfaceVersion  = "0.13.3"
val assertjVersion         = "3.26.3"
val mockitoVersion         = "5.12.0"
val commonsCompressVersion = "1.26.2"

trait CommonJava extends JavaModule {
  override def javacOptions = Seq("--release", "25", "-Xlint:all", "-Xlint:-processing")

  override def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      coursier.MavenRepository("https://central.sonatype.com/repository/maven-snapshots/")
    )
  }

	  trait CommonTests extends JavaModuleTests with TestModule.Junit5 {
	    override def forkArgs = Seq("-Dnet.bytebuddy.experimental=true")

	    override def ivyDeps = Agg(
	      ivy"org.junit.jupiter:junit-jupiter:${junitVersion}",
	      ivy"org.junit.platform:junit-platform-launcher:${junitPlatformVersion}",
	      ivy"com.github.sbt.junit:jupiter-interface:${junitInterfaceVersion}",
	      ivy"org.assertj:assertj-core:${assertjVersion}",
	      ivy"org.mockito:mockito-core:${mockitoVersion}",
	      ivy"org.mockito:mockito-junit-jupiter:${mockitoVersion}"
	    )
	  }
}

object core extends CommonJava {
  object test extends CommonTests {
    override def sources = T.sources(millSourcePath / os.up / "test")
  }
}

object configParser extends CommonJava {
  override def millSourcePath = build.millSourcePath / "config-parser"
  override def moduleDeps = Seq(core)
  override def ivyDeps = Agg(
    ivy"com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${jacksonVersion}",
    ivy"com.fasterxml.jackson.core:jackson-databind:${jacksonVersion}"
  )
  object test extends CommonTests {
    override def sources = T.sources(millSourcePath / os.up / "test")
    override def resources = T.sources(millSourcePath / os.up / "test" / "resources")
  }
}

object executor extends CommonJava {
  override def moduleDeps = Seq(configParser)
  override def ivyDeps = Agg(
    ivy"org.apache.commons:commons-compress:${commonsCompressVersion}",
    ivy"org.slf4j:slf4j-api:${slf4jVersion}",
    ivy"ch.qos.logback:logback-classic:${logbackVersion}"
  )
  object test extends CommonTests {
    override def sources = T.sources(millSourcePath / os.up / "test")
  }
}

object tui extends CommonJava {
  override def moduleDeps = Seq(executor)
  object test extends CommonTests {
    override def sources = T.sources(millSourcePath / os.up / "test")
  }
}

object app extends CommonJava {
  override def moduleDeps = Seq(tui)
  object test extends CommonTests {
    override def sources = T.sources(millSourcePath / os.up / "test")
  }
}

object cli extends CommonJava {
  override def moduleDeps = Seq(app)
  override def ivyDeps = Agg(
    ivy"info.picocli:picocli:${picocliVersion}"
  )
  override def compileIvyDeps = Agg(
    ivy"info.picocli:picocli-codegen:${picocliVersion}"
  )
  override def javacOptions = super.javacOptions() ++ Seq("-Aproject=sysboot")
  override def mainClass = Some("dev.sysboot.cli.Main")

  def graalConfig = T.input {
    val graalDir = build.millSourcePath / "graal"
    Seq(
      PathRef(graalDir / "reflect-config.json"),
      PathRef(graalDir / "resource-config.json")
    )
  }

  def nativeImage = T {
    val jar = assembly()
    graalConfig()
    val out = T.dest / "sysboot"
    val graalDir = build.millSourcePath / "graal"
    os.proc(
      "native-image",
      "-jar", jar.path,
      "--no-fallback",
      "-H:+UnlockExperimentalVMOptions",
      "-H:+ReportExceptionStackTraces",
      s"-H:ReflectionConfigurationFiles=${graalDir / "reflect-config.json"}",
      s"-H:ResourceConfigurationFiles=${graalDir / "resource-config.json"}",
      "-H:-UnlockExperimentalVMOptions",
      "--enable-url-protocols=http,https",
      "--initialize-at-run-time=org.slf4j,ch.qos.logback,org.jline",
      "-o", out
    ).call(stdout = os.Inherit, stderr = os.Inherit)
    PathRef(out)
  }

  object test extends CommonTests {
    override def sources = T.sources(millSourcePath / os.up / "test")
  }
}

object integrationTests extends CommonJava {
  override def millSourcePath = build.millSourcePath / "integration-tests"
  override def moduleDeps = Seq(app)
  object test extends CommonTests {
    override def sources = T.sources(millSourcePath / os.up / "test")
  }
}
