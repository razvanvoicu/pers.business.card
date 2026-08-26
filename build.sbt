import Dependencies._
import org.scalajs.linker.interface.ModuleKind
//import sbtcrossproject.CrossPlugin.autoImport.*
//import scalajscrossproject.ScalaJSCrossPlugin.autoImport.*

// One ignored, machine-specific locator points to a shared configuration file. Its contents are reread by every
// task, while relative secret paths inside it are resolved from the shared file's directory.
val repositoryDir = file(".").getCanonicalFile
val localConfigDir = repositoryDir / ".local"
val appConfigPathPrefix = "APPCONFIGPATH="
val appConfigFile = LocalConfigBuild.requiredPath(localConfigDir, appConfigPathPrefix, repositoryDir)
val supportedAppConfigKeys = Set(
  "LOCAL_BASE_URL",
  "ARTIFACT_BASE_URL",
  "PUBLIC_BASE_URL",
  "ARTIFACT_PORT",
  "GCP_PROJECT_ID",
  "FIRESTORE_DATABASE_ID",
  "FIRESTORE_LOCATION",
  "GCLOUD_REGION",
  "ARTIFACT_REGISTRY_REPOSITORY",
  "GCLOUD_SERVICE_ACCOUNT",
  "CARD_NAME",
  "CARD_DESIGNATION",
  "CARD_TAGLINE",
  "CARD_LINK1",
  "CARD_LINK2",
  "CARD_LINK3",
  "CARD_LINK4"
)
val initialAppConfig = AppConfigBuild.load(appConfigFile, supportedAppConfigKeys)

// gcloud's own well-known Application Default Credentials location, baked into the standalone `artifact` image
// (only) so `docker run` can reach Firestore outside a GCP platform's own workload identity. The Cloud Run
// image never includes this file. Generate it with `gcloud auth application-default login`. Optional: if missing,
// the standalone image is still built, but the container will need credentials supplied another way.
val adcFile = file(
  if (sys.props("os.name").toLowerCase.contains("win"))
    s"${sys.env.getOrElse("APPDATA", "")}/gcloud/application_default_credentials.json"
  else
    s"${sys.props("user.home")}/.config/gcloud/application_default_credentials.json"
)

// TEMPLATE SETTING: the target platform for the Docker image, independent of the machine running `sbt artifact`
// (e.g. building on Apple Silicon for an amd64 deployment host). BuildKit cross-builds via emulation as needed.
val dockerPlatform = "linux/amd64"

lazy val deploymentArtifact = taskKey[Unit]("Build the backend's Docker image")
lazy val deployGCloud = taskKey[Unit](
  "Build a secret-free image, push it to Artifact Registry, and deploy it to Google Cloud Run"
)
lazy val firestoreEmulator = taskKey[Unit]("Start the Firestore emulator with generated, untracked project config")

lazy val readmeToHtml = taskKey[Unit]("Render README.rst to target/README.html via docutils")
lazy val appBuildConfig = taskKey[AppConfigBuild.Config]("Read and validate the shared application configuration")

addCommandAlias("artifact", "deploymentArtifact")
addCommandAlias("fmt", ";scalafixAll;e2etest/scalafixAll;scalafmtRepo")
addCommandAlias("fmtCheck", ";scalafixAll --check;e2etest/scalafixAll --check;scalafmtCheckRepo")

ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version := "1.0"
ThisBuild / organization := "sg.raz"
ThisBuild / organizationName := "raz"
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard"
)
ThisBuild / semanticdbEnabled := true

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("shared"))
  .settings(
    name := "shared",
    libraryDependencies += "dev.zio" %%% "zio-json" % zioJsonVersion
  )

lazy val sharedJS = shared.js
lazy val sharedJVM = shared.jvm

lazy val frontend = (project in file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(sharedJS)
  .settings(
    name := "frontend",
    coverageEnabled := false,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.NoModule)),
    libraryDependencies ++= Seq(
      "com.raquo" %%% "laminar" % laminarVersion,
      "org.scala-js" %%% "scalajs-dom" % scalajsDomVersion,
      "org.scalameta" %%% "munit" % munitVersion % Test
    )
  )

// Copies the linked Scala.js output into the backend's classpath under `web/` to facilitate packaging and local running
lazy val frontendAssets = Def.task {
  val linkedDir = (frontend / Compile / fastLinkJSOutput).value
  val outDir = (Compile / resourceManaged).value / "web"
  IO.createDirectory(outDir)
  linkedDir.listFiles().filter(_.isFile).toSeq.map { src =>
    val dest = outDir / src.getName
    IO.copyFile(src, dest)
    dest
  }
}

// Parses a `KEY=value` env file in the format shared by prod.env, test.env, and the runApp launcher, so any such
// file can seed `sbt run`'s forked process without deployment identity living in the source.
def parseEnvFile(file: File): Map[String, String] =
  if (!file.isFile) Map.empty
  else
    IO.readLines(file)
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .flatMap { line =>
        line.split("=", 2) match {
          case Array(key, value) => Some(key.trim -> value.trim)
          case _                 => None
        }
      }
      .toMap

// Converts a Map into sorted `KEY=value` lines suitable for a shell-sourced env file (see prod.env / test.env),
// validating none of the values themselves contain a line break.
def envFileLines(values: Map[String, String]): Seq[String] =
  values.toSeq.sortBy(_._1).map { case (key, value) =>
    if (value.exists(character => character == '\r' || character == '\n'))
      sys.error(s"Environment value $key contains a line break and cannot be written to prod.env")
    s"$key=$value"
  }

// Builds the context shared by standalone and Cloud Run images. Only explicitly supplied generatedEnv values
// and the optional ADC file enter the context, making the Cloud Run caller able to guarantee a secret-free image.
def stageDockerContext(
    appJar: File,
    runtimeJars: Seq[File],
    resources: File,
    dockerDir: File,
    generatedEnv: Map[String, String],
    includedAdc: Option[File]
): Unit = {
  val duplicateJarNames = runtimeJars.groupBy(_.getName).collect {
    case (jarName, jars) if jars.size > 1 => jarName
  }
  if (duplicateJarNames.nonEmpty)
    sys.error(s"Runtime dependency filename collision: ${duplicateJarNames.mkString(", ")}")

  IO.delete(dockerDir)
  IO.createDirectory(dockerDir / "lib")
  IO.createDirectory(dockerDir / "adc")
  val applicationJar = new java.util.jar.JarFile(appJar)
  try {
    val requiredWebAssets = Seq(
      "web/favicon.ico",
      "web/icon-192.png",
      "web/icon-512.png",
      "web/manifest.webmanifest"
    )
    val missingWebAssets = requiredWebAssets.filter(applicationJar.getJarEntry(_) == null)
    if (missingWebAssets.nonEmpty)
      sys.error(s"Application JAR ${appJar.getAbsolutePath} is missing ${missingWebAssets.mkString(", ")}")
  } finally applicationJar.close()
  IO.copyFile(appJar, dockerDir / "app.jar")
  runtimeJars.foreach(jar => IO.copyFile(jar, dockerDir / "lib" / jar.getName))

  val sourceEnv = resources / "prod.env"
  if (!sourceEnv.isFile) sys.error(s"Missing packaging resource: ${sourceEnv.getAbsolutePath}")
  val envLines = IO.readLines(sourceEnv) ++ envFileLines(generatedEnv)
  // Explicit "\n" rather than IO.writeLines: prod.env is sourced by a POSIX shell even when built on Windows.
  IO.write(dockerDir / "prod.env", envLines.map(_ + "\n").mkString)

  Seq("runApp", "Dockerfile").foreach { fileName =>
    val source = resources / fileName
    if (!source.isFile) sys.error(s"Missing packaging resource: ${source.getAbsolutePath}")
    IO.copyFile(source, dockerDir / fileName)
  }

  includedAdc.foreach(file => IO.copyFile(file, dockerDir / "adc" / "application_default_credentials.json"))
}

def cloudRunEnvYaml(values: Map[String, String]): String =
  values.toSeq
    .sortBy(_._1)
    .map { case (key, value) =>
      if (!key.matches("[A-Z_][A-Z0-9_]*")) sys.error(s"Invalid Cloud Run environment variable name: $key")
      if (value.exists(character => character == '\r' || character == '\n'))
        sys.error(s"Cloud Run environment value $key contains a line break")
      s"$key: '${value.replace("'", "''")}'\n"
    }
    .mkString

def runCommand(command: Seq[String], workingDirectory: File, description: String): Unit = {
  val exitCode =
    try sys.process.Process(command, workingDirectory).!
    catch {
      case _: java.io.IOException =>
        sys.error(s"Could not run ${command} while $description; ensure it is installed and on PATH.")
    }
  if (exitCode != 0) sys.error(s"Failed while $description (exit code $exitCode)")
}

def platformCli(name: String): Seq[String] =
  if (sys.props("os.name").toLowerCase.contains("win")) Seq("cmd", "/d", "/c", s"$name.cmd")
  else Seq(name)

def stageFirebaseConfiguration(repository: File, stageDir: File, projectId: String): File = {
  val sourceConfig = repository / "firebase.json"
  if (!sourceConfig.isFile) sys.error(s"Missing Firebase emulator configuration: ${sourceConfig.getAbsolutePath}")
  val escapedProjectId = projectId.replace("\\", "\\\\").replace("\"", "\\\"")

  IO.delete(stageDir)
  IO.createDirectory(stageDir)
  IO.copyFile(sourceConfig, stageDir / "firebase.json")
  IO.write(
    stageDir / ".firebaserc",
    s"""|{
        |  "projects": {
        |    "default": "$escapedProjectId"
        |  }
        |}
        |""".stripMargin
  )
  stageDir
}

def firebaseEmulatorCommand(projectId: String): Seq[String] =
  platformCli("firebase") ++
    Seq("emulators:start", "--only", "firestore", s"--project=$projectId", "--config", "firebase.json")

// True if something is already accepting connections on host:port. Used so the e2etest suite doesn't silently
// test against a stray leftover process on the backend's port.
def isPortOpen(host: String, port: Int): Boolean =
  try {
    val socket = new java.net.Socket()
    try { socket.connect(new java.net.InetSocketAddress(host, port), 500); true }
    finally socket.close()
  } catch { case _: Throwable => false }

lazy val backend = (project in file("backend"))
  .dependsOn(sharedJVM)
  .settings(
    name := "backend",
    libraryDependencies ++= Seq(
      classGraph,
      zioHttp,
      zioLogging,
      firestore,
      firestoreAdmin,
      munit % Test,
      gson % Test
    ),
    Compile / resourceGenerators ++= Seq(frontendAssets.taskValue),
    run / fork := true,
    run / connectInput := true,
    // Some networks hand out AAAA (IPv6) records for googleapis.com without actually routing IPv6, which
    // otherwise surfaces as NoRouteToHostException from outbound Firestore calls; prefer IPv4 to avoid that.
    // The Docker image's `runApp` launcher passes the same flag directly.
    run / javaOptions += "-Djava.net.preferIPv4Stack=true",
    appBuildConfig := AppConfigBuild.load(appConfigFile, supportedAppConfigKeys),
    run / envVars ++= {
      val config = appBuildConfig.value
      parseEnvFile((Compile / resourceDirectory).value / "test.env") ++
        AppConfigBuild.gcpRuntimeEnv(config) ++
        AppConfigBuild.cardRuntimeEnv(config) ++
        PublicBaseUrlBuild.localConfigEnv("LOCAL_BASE_URL", config.required("LOCAL_BASE_URL"))
    }
  )

// End-to-end Selenium suite. Its `test` task doesn't just run tests: it first checks Chrome/Selenium are
// actually launchable, then starts the real backend as a background process compiled with scoverage
// instrumentation (via a nested `sbt ... coverage run`, mirroring the coverage workflow already documented
// below), so the HTTP traffic these tests generate counts toward backend coverage alongside the unit suite's
// own. `coverageReport` is left as a separate, manual step (see Tests and coverage) rather than triggered here,
// so a unit-test run and an e2etest run can both contribute to one combined report without either one
// clobbering the other's data.
lazy val e2etest = (project in file("e2etest"))
  .settings(
    name := "e2etest",
    coverageEnabled := false,
    libraryDependencies ++= Seq(selenium % Test, munit % Test),
    Test / fork := true,
    Test / envVars ++= {
      val config = (backend / appBuildConfig).value
      Map(
        "E2E_BASE_URL" -> PublicBaseUrlBuild
          .localConfigEnv("LOCAL_BASE_URL", config.required("LOCAL_BASE_URL"))
          .apply("PUBLIC_BASE_URL")
      )
    },
    Test / test := Def.taskDyn {
      val log = streams.value.log
      val repoRoot = (ThisBuild / baseDirectory).value
      val config = (backend / appBuildConfig).value
      val localRuntimeEnv = PublicBaseUrlBuild.localConfigEnv("LOCAL_BASE_URL", config.required("LOCAL_BASE_URL"))
      val localBaseUrl = localRuntimeEnv("PUBLIC_BASE_URL")
      val localPort = localRuntimeEnv("PORT").toInt

      log.info("Checking that Selenium can launch Chrome...")
      (Test / runMain).toTask(" sgrv.e2e.SeleniumCheck").value

      // A stray already-running process on this port (e.g. a Docker container left over from manual testing)
      // would otherwise make the readiness poll below pass instantly against THAT process instead of the
      // freshly coverage-instrumented one this task is about to start — silently testing the wrong instance
      // and recording zero coverage, while still reporting green.
      if (isPortOpen("127.0.0.1", localPort))
        sys.error(
          s"Port $localPort is already in use by something else; stop it first " +
            "so the E2E suite can be sure it's talking to the coverage-instrumented backend this task starts."
        )

      log.info("Starting the backend in the background with scoverage coverage enabled...")
      val backendLog = (Test / target).value / "e2e-backend.log"
      val backendProcess = new java.lang.ProcessBuilder("sbt", "project backend", "coverage", "run")
        .directory(repoRoot)
        .redirectErrorStream(true)
        .redirectOutput(backendLog)
        .start()

      // `run / fork := true` means this nested sbt process itself forks a *child* JVM to run sgrv.be.Main;
      // stopping just the sbt process (the direct child of the ProcessBuilder above) leaves that grandchild
      // running and still bound to the port. Walk the whole descendant tree instead.
      def stopBackendTree(): Unit = {
        val handle = backendProcess.toHandle
        handle.descendants().forEach(p => p.destroy())
        backendProcess.destroy()
        val exitedCleanly =
          backendProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) &&
            !handle.descendants().anyMatch(_.isAlive)
        if (!exitedCleanly) {
          handle.descendants().forEach(p => p.destroyForcibly())
          backendProcess.destroyForcibly()
        }
      }

      val ready = {
        val client = java.net.http.HttpClient.newHttpClient()
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(s"$localBaseUrl/")).GET().build()
        val deadline = System.currentTimeMillis() + 90000
        var upAt: Option[Long] = None
        while (upAt.isEmpty && System.currentTimeMillis() < deadline && backendProcess.isAlive) {
          try {
            client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
            upAt = Some(System.currentTimeMillis())
          } catch { case _: Throwable => Thread.sleep(500) }
        }
        upAt.isDefined
      }
      if (!ready) {
        stopBackendTree()
        sys.error(
          s"Backend did not become ready within 90s (see $backendLog for its output); " +
            "not running the E2E suite."
        )
      }
      log.info("Backend is up; running the E2E suite...")

      Def.task {
        try {
          val output = (Test / executeTests).value
          if (output.overall != TestResult.Passed) sys.error(s"E2E tests: ${output.overall}")
        } finally {
          log.info("Stopping the backend...")
          stopBackendTree()
        }
      }
    }.value
  )

lazy val root = {
  Project(id = "root", base = file("."))
    .aggregate(sharedJS, sharedJVM, frontend, backend)
    .settings(
      name := "persbizcard",
      coverageEnabled := false,
      publish / skip := true,
      run / aggregate := false,
      Compile / run := (backend / Compile / run).evaluated,
      Test / test / aggregate := false,
      Test / test := Def.taskDyn {
        Def.task {
          (frontend / Test / test).value
          (backend / Test / test).value
        }
      }.value,
      clean / aggregate := false,
      clean := {
        (sharedJS / clean).value
        (sharedJVM / clean).value
        (frontend / clean).value
        (backend / clean).value
        IO.delete((Compile / target).value)
      },
      firestoreEmulator := {
        val config = (backend / appBuildConfig).value
        val projectId = config.required("GCP_PROJECT_ID")
        val stageDir = stageFirebaseConfiguration(repositoryDir, (Compile / target).value / "firebase", projectId)
        runCommand(firebaseEmulatorCommand(projectId), stageDir, "running the Firestore emulator")
      },
      readmeToHtml := {
        val log = streams.value.log
        val repoRoot = (ThisBuild / baseDirectory).value
        val outputDir = (Compile / target).value
        IO.createDirectory(outputDir)
        val exitCode =
          try
            sys.process.Process(Seq("python3", "-m", "docutils", "README.rst", "target/README.html"), repoRoot).!
          catch {
            case _: java.io.IOException =>
              sys.error("python3 not found on PATH, or its docutils module isn't installed (`pip install docutils`).")
          }
        if (exitCode != 0) sys.error(s"docutils failed to render README.rst (exit code $exitCode)")
        log.success(s"Rendered README.rst to ${outputDir / "README.html"}")
      },
      deploymentArtifact := Def.taskDyn {
        val config = (backend / appBuildConfig).value
        val deploymentEnv = PublicBaseUrlBuild.configEnv(
          "ARTIFACT_BASE_URL",
          config.required("ARTIFACT_BASE_URL")
        ) ++ Map("PORT" -> config.requiredPort("ARTIFACT_PORT")) ++ AppConfigBuild.gcpRuntimeEnv(config) ++
          AppConfigBuild.cardRuntimeEnv(config)
        clean.value
        Def.task {
          val log = streams.value.log
          val appJar = (backend / Compile / packageBin).value
          val sharedJar = (sharedJVM / Compile / packageBin).value
          val runtimeJars = (
            (backend / Runtime / dependencyClasspath).value.map(_.data).filter(_.isFile) ++ Seq(sharedJar)
          ).distinct
          val resources = (backend / Compile / resourceDirectory).value
          val outputDir = (backend / Compile / target).value

          // Stage the Docker build context: app.jar, lib/*.jar, a secret-bearing prod.env, runApp (the image's
          // entrypoint), the Dockerfile itself, and (if available) local ADC for testing outside a GCP platform's
          // own workload identity.
          val dockerDir = outputDir / "docker"
          stageDockerContext(
            appJar,
            runtimeJars,
            resources,
            dockerDir,
            deploymentEnv,
            if (adcFile.isFile) Some(adcFile) else None
          )
          if (!adcFile.isFile)
            log.warn(
              s"No Application Default Credentials found at ${adcFile.getAbsolutePath}; building the Docker image " +
                "without them. Run `gcloud auth application-default login` first, or supply credentials to the " +
                "container another way (e.g. GCP workload identity on Cloud Run/GKE/GCE)."
            )

          val imageTag = s"${name.value}:${version.value}"
          val dockerBuildArgs = Seq(
            "docker",
            "build",
            "--platform",
            dockerPlatform,
            "-t",
            imageTag,
            "-t",
            s"${name.value}:latest",
            "."
          )
          runCommand(dockerBuildArgs, dockerDir, s"building Docker image $imageTag")
          log.success(s"Built Docker image for $dockerPlatform: $imageTag (and ${name.value}:latest)")
        }
      }.value,
      deployGCloud := Def.taskDyn {
        val config = (backend / appBuildConfig).value
        val deploymentEnv = PublicBaseUrlBuild.configEnv(
          "PUBLIC_BASE_URL",
          config.required("PUBLIC_BASE_URL")
        ) ++ AppConfigBuild.gcpRuntimeEnv(config) ++ AppConfigBuild.cardRuntimeEnv(config)
        val gcloudProject = config.required("GCP_PROJECT_ID")
        val gcloudRegion = config.required("GCLOUD_REGION")
        val artifactRepository = config.required("ARTIFACT_REGISTRY_REPOSITORY")
        val artifactRegistry = s"$gcloudRegion-docker.pkg.dev/$gcloudProject/$artifactRepository"
        val gcloudServiceAccount = config.required("GCLOUD_SERVICE_ACCOUNT")
        clean.value
        Def.task {
          val log = streams.value.log
          val appJar = (backend / Compile / packageBin).value
          val sharedJar = (sharedJVM / Compile / packageBin).value
          val runtimeJars = (
            (backend / Runtime / dependencyClasspath).value.map(_.data).filter(_.isFile) ++ Seq(sharedJar)
          ).distinct
          val resources = (backend / Compile / resourceDirectory).value
          val outputDir = (backend / Compile / target).value

          val dockerDir = outputDir / "docker-gcloud"
          stageDockerContext(appJar, runtimeJars, resources, dockerDir, Map.empty, None)

          val serviceName = name.value
          val remoteImage = s"$artifactRegistry/$serviceName:${version.value}"
          val registryHost = s"$gcloudRegion-docker.pkg.dev"
          val gcloudCmd = platformCli("gcloud")

          runCommand(
            gcloudCmd ++ Seq("auth", "configure-docker", registryHost, "--quiet"),
            repositoryDir,
            s"configuring Docker authentication for $registryHost"
          )
          runCommand(
            Seq("docker", "build", "--platform", dockerPlatform, "-t", remoteImage, "."),
            dockerDir,
            s"building secret-free Cloud Run image $remoteImage"
          )
          runCommand(Seq("docker", "push", remoteImage), repositoryDir, s"pushing $remoteImage")

          val envFile = java.nio.file.Files.createTempFile(s"$serviceName-cloud-run-", ".yaml").toFile
          try {
            IO.write(envFile, cloudRunEnvYaml(deploymentEnv))
            runCommand(
              gcloudCmd ++
                Seq(
                  "run",
                  "deploy",
                  serviceName,
                  "--image",
                  remoteImage,
                  "--project",
                  gcloudProject,
                  "--region",
                  gcloudRegion,
                  "--service-account",
                  gcloudServiceAccount,
                  "--port",
                  "8080",
                  "--allow-unauthenticated",
                  "--env-vars-file",
                  envFile.getAbsolutePath,
                  "--quiet"
                ),
              repositoryDir,
              s"deploying Cloud Run service $serviceName"
            )
          } finally IO.delete(envFile)

          log.success(s"Deployed $remoteImage to Cloud Run service $serviceName in $gcloudRegion")
        }
      }.value
    )
}
